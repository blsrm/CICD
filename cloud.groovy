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
def PACKAGE_VERSION = 'none'
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
        SONARQUBE_URL='http://217.28.199.246:81/'
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
                            PACKAGE_VERSION = packageJSON.version
                            props = readProperties  file: "deploy/config/properties/${ENV_TYPE}/${ENV_NAME}.properties"
                            // Updating property with the new concatenated values
                            props.put("DOCKER_IMAGE_WITH_TAG", "${ENV_NAME}-${APPLICATION_NAME}:${PACKAGE_VERSION}-${BUILD_NUMBER}")
                            print props
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
                // Building a docker image and pushing it to the docker registry(ECR)
                script {
                    dir(env.JOB_NAME) {
                        try {
                            timeout(time: 10, unit: 'MINUTES') {
                                docker.build("${props.DOCKER_IMAGE_WITH_TAG}")
                                print "Docker image successfuly build with the name ${props.DOCKER_IMAGE_WITH_TAG}"
                                docker.withRegistry("https://${props.ECR_DOMAIN}", "ecr:${props.REGION_NAME}:${props.KEYS_CREDENTIAL_ID}") {
                                    docker.image("${props.DOCKER_IMAGE_WITH_TAG}").push("${PACKAGE_VERSION}-${BUILD_NUMBER}")
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
        stage('Cloud: Deploy') {
            steps {
                script {
                    dir(env.JOB_NAME) {
                        try {
                            timeout(time: 10, unit: 'MINUTES') {
                                // Using credentials binding get the AWS access keys as environment variables
                                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'dev-aws-keys', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                                    // Reading environment specific input parameter JSON of cloud formation template
                                    String paramsJSON = "deploy/params/${ENV_NAME}.json"
                                    print "Reading ${ENV_NAME} paramerter JSON"
                                    def params = readJSON file: paramsJSON
                                    // Iterate over the object to update the ECRImage attribute value with and updated docker image tag reference
                                    params.each { item ->
                                        if (item.ParameterKey == 'ECRImage') {
                                            String paramVaue = "${props.ECR_DOMAIN}/${props.DOCKER_IMAGE_WITH_TAG}"
                                            item.ParameterValue = paramVaue
                                            return
                                        }
                                    }
                                    // Writing updated JSON conent to the same file
                                    writeJSON file: paramsJSON, json: params, pretty: 4
                                    print "The ${ENV_NAME} paramerter JSON successfully updated with the new Docker image reference"
                                    String stack_cmd = 'update-stack'
                                    if (env.CREATE_STACK == "true") {
                                        stack_cmd = 'create-stack'
                                    }
                                    // Invoke cloud stack create/update request using AWS-CLI
                                    sh "aws --region ${props.REGION_NAME} cloudformation ${stack_cmd} --stack-name ${ENV_NAME}-${APPLICATION_NAME} --template-body file://deploy/cfn/rettsplusweb_ui.json --parameters file://deploy/params/${ENV_NAME}.json --capabilities CAPABILITY_NAMED_IAM"
                                }
                            }
                        }  catch (e) {
                            STAGE_EXCEPTION = 'Exception occured on Docker Image creation  ' + e.toString();
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
