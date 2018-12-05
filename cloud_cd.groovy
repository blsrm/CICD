// Environments
environments = [
    'dev',
    'staging',
    'production'
]

// Environment types
environment_types = [
    'cloud',
    'physical'
]

environments_choice = environments.join('\n')
environment_types_choice = environment_types.join('\n')

def RELEASE_VERSION = 'none'
def STAGE_EXCEPTION = 'none'

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
            description: 'Git branch or tag to build from',
        )
    }
    agent any
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
                    try {
                        dir(env.JOB_NAME) {
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

                            println "Reading package JSON"
                            // Reading version from package JSON and exporting it as environment variable
                            def packageJSON = readJSON file: "package.json"
                            RELEASE_VERSION = packageJSON.version
                            println "The package version ${RELEASE_VERSION} will be used for deployment"
                        }
                    }
                    catch (err) {
                        STAGE_EXCEPTION = 'Exception occured on Docker Image creation  ' + err.toString();
                        throw err
                    }
                }
            }   
        }
        stage('Loading config') {
            steps {
                script {
                    dir(env.JOB_NAME) {
                        try {
                            // Reading configuration details from a specific environment properties
                            // file using utility plugin
                            props = readProperties  file: "deploy/config/properties/${ENV_TYPE}/${ENV_NAME}.properties"
                            // Updating property with the new values
                            props.put("DOCKER_IMAGE_WITH_TAG", "${ENV_NAME}-${APPLICATION_NAME}:${RELEASE_VERSION}-${BUILD_NUMBER}")
                        }
                        catch (err) {
                            STAGE_EXCEPTION = 'Exception occured on Docker Image creation  ' + err.toString();
                            throw err
                        }
                    }
                }
            }
        }
        stage('Downloading release package') {
            steps {
                script {
                    try {
                        dir(env.JOB_NAME) {
                            timeout(time: 10, unit: 'MINUTES') {
                                print "Downloading release package"
                                // Forming a new string with applicaion name, version and the extension .tgz
                                String packageName = "${env.APPLICATION_NAME}-${RELEASE_VERSION}.tgz"
                                withCredentials([usernamePassword(credentialsId: "${props.NEXUS_CRENDENTIAL_ID}", passwordVariable: 'NEXES_PASSWD', usernameVariable: 'NEXUS_USER')]) {
                                    // sh "wget --user ${NEXUS_USER} --password ${NEXES_PASSWD} ${props.PACKAGE_SOURCE_PATH}${packageName} -q --show-progress"
                                    sh "wget --user ${NEXUS_USER} --password ${NEXES_PASSWD} ${props.PACKAGE_SOURCE_PATH}${packageName}"
                                    sh "tar -xvzf ${packageName}"
                                    sh "mv package/build ."
                                }
                            }
                        }
                    }
                    catch (err) {
                        STAGE_EXCEPTION = 'Exception occured on Docker Image creation  ' + err.toString();
                        throw err
                    }
                }
            }
        }
        stage('Docker Image build and push') {
            steps {
                // Building a docker image and pushing it to the docker registry(ECR)
                script {
                    try {
                        dir(env.JOB_NAME) {
                            timeout(time: 10, unit: 'MINUTES') {
                                docker.build("${props.DOCKER_IMAGE_WITH_TAG}")
                                print "Docker image successfuly built with the name ${props.DOCKER_IMAGE_WITH_TAG}"
                                docker.withRegistry("https://${props.ECR_DOMAIN}", "ecr:${props.REGION_NAME}:${props.KEYS_CREDENTIAL_ID}") {
                                    docker.image("${props.DOCKER_IMAGE_WITH_TAG}").push("${RELEASE_VERSION}-${BUILD_NUMBER}")
                                }
                            }
                        }
                    }
                    catch (err) {
                        STAGE_EXCEPTION = 'Exception occured on Docker Image creation  ' + err.toString();
                        throw err
                    }
                }
            }
        }
        stage('Cloud: Deploy') {
            steps {
                script {
                    try {
                        dir(env.JOB_NAME) {
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
                        }
                    } catch (err) {
                        STAGE_EXCEPTION = 'Exception occured on Docker Image creation  ' + err.toString();
                        throw err
                    }
                }
            }
        }
    }
}
