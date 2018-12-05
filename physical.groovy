// Environments
environments = [
    'dev',
    'qa'
]
// Environment types
environment_types = [
    'cloud',
    'physical'
]

environments_choice = environments.join('\n')
environment_types_choice = environment_types.join('\n')

def GIT_CHANGES = 'none'
def STAGE_EXCEPTION = 'none'
def AUTHOR = 'none'

pipeline {
    parameters {
        choice(
            name: 'ENV_NAME',
            choices: environments_choice,
            description: 'Deploy the code changes to specified environment',
        )
        choice(
            name: 'ENV_TYPE',
            choices: environment_types_choice,
            description: 'Deployment platform cloud/physical environment',
        )
        string(
            name: 'BUILD_BRANCH_OR_TAG',
            defaultValue: 'jenkins-build',
            description: 'Git branch or tag to build from',
        )
    }
    agent any
    triggers {
        GenericTrigger(
            token: 'e4d57c8405b412b0b3535f26c999653c44415803'
        )        
    }
    // Global variables commonly used for all the environments
    environment {
        APPLICATION_NAME='rettsplusweb'
        GIT_SOURCE_URL='https://github.com/Predicare/rettsplusweb.git'
        GIT_CREDENTIAL_ID='predicare-git'
    }
    stages {
        stage('Checkout: Code') {
            steps {
                script {
                    dir(env.JOB_NAME) {
                        try {
                            String gitBranch = "*/${BUILD_BRANCH_OR_TAG}"
                            // Checkout the code of specific branch from GitHUb 
                            checkout([
                                $class: 'GitSCM',
                                branches: [[name: "${gitBranch}"]],
                                doGenerateSubmoduleConfigurations: false,
                                extensions: [[$class: 'WipeWorkspace']],
                                submoduleCfg: [],
                                userRemoteConfigs: [[
                                    credentialsId: "${GIT_CREDENTIAL_ID}",
                                    name: 'origin',
                                    url: "${GIT_SOURCE_URL}"
                                ]]
                            ])
                        } catch (e) {
                            STAGE_EXCEPTION = 'Exception occured on Git Code Checkout  ' + e.toString();
                            throw e
                        }
                    }
                }
            }
        }
        stage('Loading config') {
            steps {
                script {
                    dir(env.JOB_NAME) {
                        try {
                            // Reading configuration details from a specifiv environment properties
                            // file using utility plugin
                            println "Reading package JSON"
                            // Reading version from package JSON and exporting it as environment variable
                            def packageJSON = readJSON file: "package.json"
                            RELEASE_VERSION = packageJSON.version
                            props = readProperties  file: "deploy/config/properties/${ENV_NAME}.properties"
                            // Updating property with the new concatenated values
                            props.put("NEXUS_REGISTRY_URL", "https://${props.NEXUS_HOST}:${props.NEXUS_DOCKER_PORT}")
                            props.put("DOCKER_URI", "${props.NEXUS_HOST}:${props.NEXUS_DOCKER_PORT}")
                            props.put("SSH_CONNECT", "${props.DEPLOY_SERVER_USER_NAME}@${props.DEPLOY_SERVER_HOST}")
                            props.put("DOCKER_IMAGE_WITH_TAG", "${ENV_NAME}-${APPLICATION_NAME}:${RELEASE_VERSION}-${BUILD_NUMBER}")
                        }
                        catch (err) {
                            STAGE_EXCEPTION = 'Exception occured on loading read properties  ' + err.toString();
                            throw err
                        }
                    }
                }
            }
        }
        stage("last-changes") {
            steps {
                script {
                    dir(env.JOB_NAME) {
                        try {
                            def publisher = LastChanges.getLastChangesPublisher "PREVIOUS_REVISION", "SIDE", "LINE", true, true, "", "", "", "", ""
                                publisher.publishLastChanges()
                                def changes = publisher.getLastChanges()
                                println(changes.getEscapedDiff())
                                for (commit in changes.getCommits()) {
                                    def commitInfo = commit.getCommitInfo()
                                    def commitMessage = commitInfo.getCommitMessage()
                                    AUTHOR = commitInfo.getCommitterName()
                                }
                        } catch (e) {
                            STAGE_EXCEPTION = 'Exception occured on Compare Git Last changes  ' + e.toString();
                            throw e
                        }
                    }
                }
            }
        }
        stage('sonar') {
            steps {
                script {
                    dir(env.JOB_NAME) {
                        def scannerHome = tool 'SonarQube Scanner 2.8';
                        withSonarQubeEnv('sonar') {
                            try {
                                sh "${scannerHome}/sonar-scanner"
                            }
                            catch (e) {
                                STAGE_EXCEPTION = 'Exception occured on Sonar Qube analysis  ' + e.toString();
                                throw e
                            }
                        }
                    }
                }
            }
        }
        stage('Build artifacts and Publish') {
            steps {
                script {
                    dir(env.JOB_NAME) {
                        try {
                            timeout(time: 10, unit: 'MINUTES') {
                                sh "yarn install"
                                // Execute Unit test case
                                sh "npm run testOnJenkins"
                                // publish html
                                publishHTML target: [
                                    allowMissing: false,
                                    alwaysLinkToLastBuild: false,
                                    keepAll: true,
                                    reportDir: 'coverage',
                                    reportFiles: 'lcov-report/index.html',
                                    reportName: 'lcov-report'
                                ]
                                sh "npm run generate-doc"
                                sh "npm run build"
                                sh "npm publish"
                            }
                        } catch (e) {
                            STAGE_EXCEPTION = 'Exception occured on Building artifacts and publish  ' + e.toString();
                            throw e
                        }
                    }
                }
            }
        }
        stage('Docker Image build and push') {
            steps {
                // Building a docker image and pushing it to the nexus docker registry
                script {
                    dir(env.JOB_NAME) {
                        try {
                            timeout(time: 10, unit: 'MINUTES') {
                                docker.withRegistry("${props.NEXUS_REGISTRY_URL}", "${props.NEXUS_CRENDENTIAL_ID}") {
                                    def customImage = docker.build("${props.DOCKER_IMAGE_WITH_TAG}", "--build-arg CONTAINER_PORT=${props.WEB_APP_PORT} .")
                                    /* Push the container to the custom Registry */
                                    customImage.push()
                                }
                            }
                        } catch (e) {
                            STAGE_EXCEPTION = 'Exception occured on Docker Image creation  ' + e.toString();
                            throw e
                        }
                    }
                }
            }
        }
        stage('SSH: Deploy') {
            steps {
                script {
                    dir(env.JOB_NAME) {
                        try {
                            timeout(time: 5, unit: 'MINUTES') {
                                // Getting the user nexus credentials details as environment variables
                                withCredentials([usernamePassword(credentialsId: "${props.NEXUS_CRENDENTIAL_ID}", passwordVariable: 'NEXES_PASSWD', usernameVariable: 'NEXUS_USER')]) {
                                    String CONTAINER_NAME = "${APPLICATION_NAME}-${ENV_NAME}"
                                    // Using sshagent Jenkins plugin - connecting to the specified physical server
                                    // and deploy the application in docker container
                                    sshagent (credentials: ["${props.SSH_CRENDENTIAL_ID}"]) {
                                        sh "ssh -o StrictHostKeyChecking=no ${props.SSH_CONNECT} uptime"
                                        sh "ssh ${props.SSH_CONNECT} docker login -u ${NEXUS_USER} -p ${NEXES_PASSWD} ${props.DOCKER_URI}"
                                        sh "ssh ${props.SSH_CONNECT} docker pull ${props.DOCKER_URI}/${props.DOCKER_IMAGE_WITH_TAG}"
                                        sh "scp -r deploy/scripts/docker_deploy.sh ${props.SSH_CONNECT}:/tmp/"
                                        sh "ssh ${props.SSH_CONNECT} bash /tmp/docker_deploy.sh ${CONTAINER_NAME} ${props.DOCKER_URI}/${props.DOCKER_IMAGE_WITH_TAG} ${props.WEB_APP_PORT}"
                                    }
                                }
                            }
                        } catch (e) {
                            STAGE_EXCEPTION = 'Exception occured on SSH deploy  ' + e.toString();
                            throw e
                        }
                    }
                }
            }
        }
    }  
    // Mail Server Configuration testing
    post {
        always {
            sh "curl ${env.JOB_URL}buildTimeGraph/png -u admin:admin -o trend.png"
            emailext mimeType: 'text/html',
                to: "${props.RECIPIENTS}",
                attachmentsPattern: '**/trend.png',
                body: '''${SCRIPT, template="build-report.groovy"}''',
                recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']],
                subject: "Build ${env.BUILD_NUMBER} was Committed by: ${env.AUTHOR} in Job ${env.JOB_NAME}"
        }
    }
}
