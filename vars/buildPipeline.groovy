import org.egov.jenkins.ConfigParser
import org.egov.jenkins.models.BuildConfig
import org.egov.jenkins.models.JobConfig

import static org.egov.jenkins.ConfigParser.getCommonBasePath

library 'ci-libs'

def call(Map pipelineParams) {
    podTemplate(yaml: """
kind: Pod
metadata:
  name: kaniko
spec:
  containers:
  - name: kaniko
    image: gcr.io/kaniko-project/executor:debug-v0.15.0
    imagePullPolicy: IfNotPresent
    command:
    - /busybox/cat
    tty: true
    env:
      - name: GIT_ACCESS_TOKEN
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: gitReadAccessToken 
      - name: token
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: gitReadAccessToken                                     
    volumeMounts:
      - name: jenkins-docker-cfg
        mountPath: /root/.docker
      - name: kaniko-cache
        mountPath: /cache            
    resources:
      requests:
        memory: "1792Mi"
        cpu: "750m"
      limits:
        memory: "3954Mi"
        cpu: "1500m"      
  - name: git
    image: docker.io/egovio/builder:2-64da60a1-version_script_update-NA
    imagePullPolicy: IfNotPresent
    command:
    - cat
    tty: true        
  volumes:
  - name: kaniko-cache
    persistentVolumeClaim:
      claimName: kaniko-cache-claim
      readOnly: true        
  - name: jenkins-docker-cfg
    projected:
      sources:
      - secret:
          name: jenkins-credentials
          items:
            - key: dockerConfigJson
              path: config.json          
"""
    ) {
        node(POD_LABEL) {

            def scmVars = checkout scm
            String REPO_NAME = env.REPO_NAME ? env.REPO_NAME : "docker.io/pk366";         
            String GCR_REPO_NAME = "asia.gcr.io/digit-egov";
            def yaml = readYaml file: pipelineParams.configFile;
            List<JobConfig> jobConfigs = ConfigParser.parseConfig(yaml, env);
            String serviceCategory = null;
            String buildNum = null;

            for (int i = 0; i < jobConfigs.size(); i++) {
                JobConfig jobConfig = jobConfigs.get(i)

                stage('Parse Latest Git Commit') {
                    withEnv(["BUILD_PATH=${jobConfig.getBuildConfigs().get(0).getWorkDir()}",
                             "PATH=alpine:$PATH"
                    ]) {
                        container(name: 'git', shell: '/bin/sh') {
                            scmVars['VERSION'] = sh(script: '/scripts/get_application_version.sh ${BUILD_PATH}', returnStdout: true).trim()
                            scmVars['ACTUAL_COMMIT'] = sh(script: '/scripts/get_folder_commit.sh ${BUILD_PATH}', returnStdout: true).trim()
                            scmVars['BRANCH'] = scmVars['GIT_BRANCH'].replaceFirst("origin/", "")
                        }
                    }
                }

                stage('Build with Kaniko') {
                    withEnv(["PATH=/busybox:/kaniko:$PATH"]) {
                        container(name: 'kaniko', shell: '/busybox/sh') {

                            for (int j = 0; j < jobConfig.getBuildConfigs().size(); j++) {
                                BuildConfig buildConfig = jobConfig.getBuildConfigs().get(j)
                                echo "Preparing to build image for: ${buildConfig.getImageName()}"
                                echo "Checking paths:"
                                echo "WorkDir: ${buildConfig.getWorkDir()}"
                                echo "Dockerfile: ${buildConfig.getDockerFile()}"
                                
                                sh "ls -al ${buildConfig.getWorkDir()} || echo 'WorkDir not found!'"
                                sh "ls -al ${buildConfig.getDockerFile()} || echo 'Dockerfile not found!'"

                                if (!fileExists(buildConfig.getWorkDir()) || !fileExists(buildConfig.getDockerFile())) {
                                    throw new Exception("Working directory or Dockerfile does not exist!")
                                }

                                String workDir = buildConfig.getWorkDir().replaceFirst(getCommonBasePath(buildConfig.getWorkDir(), buildConfig.getDockerFile()), "./")
                                String image
                                if (scmVars.BRANCH.equalsIgnoreCase("master")) {
                                    image = "${REPO_NAME}/${buildConfig.getImageName()}:v${scmVars.VERSION}-${scmVars.ACTUAL_COMMIT}-${env.BUILD_NUMBER}"
                                } else {
                                    image = "${REPO_NAME}/${buildConfig.getImageName()}:${scmVars.BRANCH}-${scmVars.ACTUAL_COMMIT}-${env.BUILD_NUMBER}"
                                }
                                
                                serviceCategory = buildConfig.getServiceCategoryName()
                                buildNum = "${scmVars.VERSION}"
                                String noPushImage = env.NO_PUSH ? env.NO_PUSH : "false"
                                
                                echo "NO_PUSH=${noPushImage}"
                                echo "ALT_REPO_PUSH=${env.ALT_REPO_PUSH ?: 'not set'}"

                                if (env.ALT_REPO_PUSH?.equalsIgnoreCase("true")) {
                                    String gcr_image = "${GCR_REPO_NAME}/${buildConfig.getImageName()}:${env.BUILD_NUMBER}-${scmVars.BRANCH}-${scmVars.VERSION}-${scmVars.ACTUAL_COMMIT}"
                                    echo "Kaniko build with GCR push for image: ${image} and ${gcr_image}"

                                    sh """
                                    /kaniko/executor -f `pwd`/${buildConfig.getDockerFile()} -c `pwd`/${buildConfig.getContext()} \
                                        --build-arg WORK_DIR=${workDir} \
                                        --build-arg token=\$GIT_ACCESS_TOKEN \
                                        --cache=true --cache-dir=/cache \
                                        --single-snapshot=true \
                                        --snapshotMode=time \
                                        --destination=${image} \
                                        --destination=${gcr_image} \
                                        --no-push=${noPushImage} \
                                        --force \
                                        --cache-repo=pk366/cache/cache \
                                        2>&1 | tee kaniko_build_output.log
                                    """
                                    echo "${image} and ${gcr_image} pushed successfully!"
                                } else {
                                    echo "Kaniko build for image: ${image}"

                                    sh """
                                    /kaniko/executor -f `pwd`/${buildConfig.getDockerFile()} -c `pwd`/${buildConfig.getContext()} \
                                        --build-arg WORK_DIR=${workDir} \
                                        --build-arg token=\$GIT_ACCESS_TOKEN \
                                        --cache=true --cache-dir=/cache \
                                        --single-snapshot=true \
                                        --snapshotMode=time \
                                        --destination=${image} \
                                        --no-push=${noPushImage} \
                                        --force \
                                        --cache-repo=pk366/cache/cache \
                                        2>&1 | tee kaniko_build_output.log
                                    """
                                    echo "${image} pushed successfully!"
                                }
                            }
                        }
                    }
                }
                // Uncomment to enable dashboard updates
                // stage ("Update dashboard") {
                //     environmentDashboard {
                //         environmentName(scmVars.BRANCH)  
                //         componentName(serviceCategory)
                //         buildNumber(buildNum)
                //     }    
                // }
            }
        }
    }
}
