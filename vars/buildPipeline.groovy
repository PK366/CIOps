def call(Map pipelineParams) {
  pipeline {
    agent {
      kubernetes {
        yamlFile 'ci/kaniko-pod.yaml'
      }
    }

    environment {
      IMAGE_NAME = pipelineParams.imageName ?: 'employee'
      DOCKERFILE_PATH = pipelineParams.dockerfilePath ?: "frontend/mono-ui/web/rainmaker/docker/${IMAGE_NAME}/Dockerfile"
      CONTEXT_DIR = pipelineParams.contextDir ?: 'frontend/mono-ui/web/rainmaker'
      NO_PUSH = pipelineParams.noPush ?: 'false'
      ALT_REPO_PUSH = pipelineParams.altRepoPush ?: 'false'
      GIT_COMMIT_SHORT = ''
      APP_VERSION = ''
    }

    stages {
      stage('Checkout') {
        steps {
          checkout([
            $class: 'GitSCM',
            branches: [[name: pipelineParams.branch ?: '*/master']],
            userRemoteConfigs: [[
              url: pipelineParams.repoUrl ?: 'https://github.com/pk366/UPYOG.git',
              credentialsId: 'git_read'
            ]]
          ])
        }
      }

      stage('Parse Latest Git Commit') {
        steps {
          container('jnlp') {
            script {
              APP_VERSION = sh(
                script: "/scripts/get_application_version.sh ${CONTEXT_DIR}",
                returnStdout: true
              ).trim()

              GIT_COMMIT_SHORT = sh(
                script: "/scripts/get_folder_commit.sh ${CONTEXT_DIR}",
                returnStdout: true
              ).trim()
            }
          }
        }
      }

      stage('Build with Kaniko') {
        steps {
          container('kaniko') {
            script {
              def versionTag = "v${APP_VERSION}-${GIT_COMMIT_SHORT}-1"
              def fullImageName = "docker.io/pk366/${IMAGE_NAME}:${versionTag}"

              echo "Preparing to build image for: ${IMAGE_NAME}"
              echo "WorkDir: ${CONTEXT_DIR}"
              echo "Dockerfile: ${DOCKERFILE_PATH}"
              echo "NO_PUSH=${NO_PUSH}"
              echo "ALT_REPO_PUSH=${ALT_REPO_PUSH}"
              echo "Kaniko build for image: ${fullImageName}"

              sh """
                ls -al ${CONTEXT_DIR}
                ls -al ${DOCKERFILE_PATH}
              """

              def kanikoCommand = """
                /kaniko/executor \\
                  -f \$(pwd)/${DOCKERFILE_PATH} \\
                  -c \$(pwd)/${CONTEXT_DIR} \\
                  --build-arg WORK_DIR=./ \\
                  --build-arg token=******** \\
                  --cache=true \\
                  --cache-dir=/cache \\
                  --single-snapshot=true \\
                  --snapshotMode=time \\
                  --destination=${fullImageName} \\
                  --no-push=${NO_PUSH} \\
                  --force \\
                  --cache-repo=pk366/cache/cache
              """

              sh "echo 'Running Kaniko command:' && echo \"${kanikoCommand}\""
              sh "${kanikoCommand} | tee kaniko_build_output.log"
            }
          }
        }
      }
    }

    post {
      always {
        echo "Build completed. Clean up if necessary."
      }
      failure {
        echo "Build failed. Check logs for details."
      }
    }
  }
}
