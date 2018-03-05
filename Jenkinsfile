#!groovy
/**
 * This is the jenkins pipeline as described in https://levigo.de/info/x/1gLZAw,
 * based on https://levigo.de/info/x/IgPFAg
 *
 * It performs the following steps:
 *   - cleanup to avoid interference with stuff built before
 *   - maven build + JUnit tests
 *   - execution arquillian-tests that were introduced in 5.5
 *   - parallel: (only on master branch)
 *       + Checking sonar code quality
 *       + owasp dependency check
 *       + build of a docker image
 *   - (opt) Performing a release (only on master branch)
 *
 * It depends on the following (non-default) plugins:
 *   - External Workspace Manager Plugin (called via closure 'exws')
 *   - Lockable Resources Plugin
 *   - SSH Agent Plugin
 *   - Stash Notifier
 *   - Workspace Cleanup Plugin
 *   - Mattermost Notification Plugin
 *   - Slack Notification Plugin
 *   - Mailer Plugin
 *
 * Other prerequisites:
 *   - There must be a private SSH key provided in jenkins global security settings with id 'jenkins-ssh'
 *   - The maven settings.xml must be configured in jenkins global security with id 'mavenSettings'
 *   - Maven 3 must be installed as tool named 'M3'
 *   - Java 8 must be installed as tool named 'JDK8'
 */

// Configurable values
// -------------------

// SonarQube Host URL
def SONAR_HOST = 'http://118.69.135.237:9091'

// Who is allowed to promote for a release? (user names or AD groups, comma separated, no blanks!)
def RELEASE_PROMOTERS = 'Gruppe_Jira_Mitarbeiter_solutions'

// List of email addresses which will be notified by the Mailer plugin upon build failures
def mailRecipients = 'b.albanese@levigo.de k.adlung@levigo.de m.nguyen@levigo.de d.gehle@levigo.de'

// Timeouts how long the individual stages might take before they fail
def timeouts = [
        unit           : 'MINUTES',
        preparation    : 10,
        compile        : 60,
        arquillian     : 20, // the operation itself should'nt take more than 3 minutes, but since it is blocking, better
        // have some reserve if parallel builds occur
        sonarcube      : 15,
        owasp          : 30, // Downloading the latest  Vulnerability Database might take a while!
        docker_image   : 15,
        regression     : 1,
        deploy         : 30,
        release_promote: 90, // This stage contains a manual trigger!
        pre_release    : 10,
        release        : 60,
        post_release   : 5
]

// If a release is triggered, the pipeline requires to use a tmp branch. What's its prefix?
def RELEASE_BRANCH_PREFIX = 'release/'

// Name of the master branch
def MASTER = 'master'

// Version of the docker image
def LATEST = 'latest'

// Name of the diskpool that is used for the jenkins build.
def DISKPOOL_NAME = 'diskpool0'

// The content of the main pom.xml, later to be read from the file system
def pom = ''

// The GWT version like defined in the main pom.xml
def pomGWTVersion = ''

def LOCAL_REPO_POSTFIX = 'm2repo'

// Caveat: We assume that all agent have mounted the extWorkspace on the same path!
def localRepoParam = ''

// The available choices for the gwt version. ATTENTION: the default value must be the same as in the main pom.xml
def GWT_VERSION_CHOICES = '2.8.2\n2.7.0'

// Name of the channel where build failures are pushed to - the same for both slack and mattermost
def NOTIFICATION_CHANNEL = 'ci_jwt'

properties([
        // These parameters must be set to a valid value, otherwise the build will fail.
        // The first "choices" value is the default, see https://docs.openstack.org/infra/jenkins-job-builder/parameters.html
        // Beware: the list is not comma-separated, use instead linefeed '\n'
        parameters([
                /**
                 *  2.7.0 (maintenance version)
                 *  2.8.2 (latest version, all older 2.8 versions are skipped)
                 */
                choice(name: 'gwt_version', choices: GWT_VERSION_CHOICES,
                        description: 'The gwt version used for the build'),
                /**
                 * Valid values as of end of 2017:
                 *  JDK7
                 *  JDK8
                 */
                choice(name: 'jdk_version', choices: 'JDK8\nJDK7', description: 'The JDK generation used for the build'),
                string(name: 'BRANCH_NAME', default: 'master'),
                string(name: 'SONATYPE_USER', default: 'admin'),
                string(name: 'SONATYPE_PASSWORD', default: 'admin123'),
                
        ]),
        [$class: 'BuildDiscarderProperty', strategy: [$class: 'LogRotator', daysToKeepStr: '7',  numToKeepStr: '']]
])

// allocate a disk from the disk pool defined in the Jenkins global config
// [JORB-98] The docbkx-maven-plugin cannot handle paths with URL encodings -> regex handling here needed
def extWorkspace = exwsAllocate diskPoolId: DISKPOOL_NAME, path: "${env.JOB_NAME.replaceAll('[\\p{Punct}\\p{Space}]', '_')}/${env.BUILD_NUMBER}"

try {
  node() {
    exws(extWorkspace) {
      localRepoParam = "-Dmaven.repo.local=${extWorkspace.completeWorkspacePath}/$LOCAL_REPO_POSTFIX"
      env.PATH = "${tool 'M3'}/bin:${tool params.jdk_version}/bin:${env.PATH}"
      echo "building with gwt version: ${params.gwt_version}"
      echo "building with jdk generation: ${params.jdk_version}"
      def defaultGWTVersion = GWT_VERSION_CHOICES.split('\n')[0]



      withEnv(["PATH=$env.PATH", "JAVA_HOME=${tool params.jdk_version}", "MVN_OPTS=$env.MVN_OPTS -XX:MaxPermSize=1024m"]) {
        withCredentials([file(credentialsId: 'mavenSettings', variable: 'MVN_SETTINGS')]) {
          stage('Preparation') {
            timeout(time: timeouts.preparation, unit: timeouts.unit) {
              checkout scm
              step([$class: 'StashNotifier']) // Notify stash of an INPROGRESS build
              pom = readFile('pom.xml')
              pomGWTVersion = getGWTVersionFromPom(pom)
              if (defaultGWTVersion != pomGWTVersion){
                throw new IllegalStateException(
                        "ERROR: This looks like the gwt version in the main pom.xml and the " +
                                "Jenkinsfile default gwt value are different. Be sure that those two values match!")
              }
            }
          }

          def profile = ""
          if (isFullBuildWithJava8AndLatestGWT(params.gwt_version, params.jdk_version, pomGWTVersion)){
            profile = "-P runGWTTestCase "
          }
          stage('Compile and run Junit tests') {
            timeout(time: timeouts.compile, unit: timeouts.unit) {
              sh "mvn -B -s $MVN_SETTINGS -gs $MVN_SETTINGS -Dorg.apache.maven.user-settings=$MVN_SETTINGS " +
                      "-Dorg.apache.maven.global-settings=$MVN_SETTINGS $localRepoParam " +
                      "-Dgwt.version=${params.gwt_version} -Dgwtplugin.version=${params.gwt_version} ${profile}" +
                      "install "
            }
          }
        }
      }
    }
  }

// this extra node is needed as otherwise the lock within would be a lock not only to the arquillian step, but also
// to the other stuff like compilation, sonar, docker build etc.
  node() {
    try {
      exws(extWorkspace) {
        // needs to be run with java 8 or higher. If we use JDK7, we get "Unsupported major.minor version 52.0",
        // see also https://stackoverflow.com/questions/22489398/unsupported-major-minor-version-52-0
        if (isFullBuildWithJava8AndLatestGWT(params.gwt_version, params.jdk_version, pomGWTVersion)) {
          env.PATH = "${tool 'M3'}/bin:${tool params.jdk_version}/bin:${env.PATH}"
          withEnv(["PATH=$env.PATH", "JAVA_HOME=${tool params.jdk_version}"]) {
            withCredentials([file(credentialsId: 'mavenSettings', variable: 'MVN_SETTINGS')]) {
              // As there are quite some issues with the webtoolkit-ee tests, make sure only one build at the time
              // is in this stage.
              // Previous errors included the contained wildfly trying to listen to port 8080, leading to a block.
              // Alas the ports were changed in the project, but here we also want to take care that two different
              // builds don't launch wildfly instances at the same time.
              stage('Arquillian') {
                lock('arquillian-exclusive') {
                  timeout(time: timeouts.compile, unit: timeouts.unit) {
                    // see https://stackoverflow.com/questions/31460999/arquillian-shrinkwrap-provide-custom-settings-xml-file/31598435#31598435
                    sh "mvn -B -s $MVN_SETTINGS -gs $MVN_SETTINGS -Dorg.apache.maven.user-settings=$MVN_SETTINGS " +
                            "-Dorg.apache.maven.global-settings=$MVN_SETTINGS $localRepoParam " +
                            "-pl com.example:webtoolkit-ee-primer -P arquillian-tests " +
                            "install"
                  }
                }
              }
            }
          }
        }
      }
    } finally {
      // if the catch block above was executed, don't do stuff here
      if ((!(env.BRANCH_NAME == MASTER)) && (currentBuild.result == null)) {
        // we obviously are not on master, so we won't proceed to the steps below -> abort successfully and notify stash
        currentBuild.result = 'SUCCESS' // Important for the Stash notifier!
        step([$class: 'StashNotifier']) // Notify stash of the build result
      }
    }
  }

  if (env.BRANCH_NAME == MASTER && isFullBuildWithJava8AndLatestGWT(params.gwt_version, params.jdk_version, pomGWTVersion)) {
    try {
      parallel(sonarCube: {
        node() {
          exws(extWorkspace) {
            env.PATH = "${tool 'M3'}/bin:${tool params.jdk_version}/bin:${env.PATH}"
            withEnv(["PATH=$env.PATH", "JAVA_HOME=${tool params.jdk_version}"]) {
              withCredentials([file(credentialsId: 'mavenSettings', variable: 'MVN_SETTINGS')]) {

                stage('SonarCube') {
                  timeout(time: timeouts.sonarcube, unit: timeouts.unit) {
                    sh "mvn -B -s $MVN_SETTINGS -gs $MVN_SETTINGS $localRepoParam -Dsonar.host.url=$SONAR_HOST " +
                            "sonar:sonar"
                  }
                }
              }
            }
          }
        }
      }, owasp: {
        node() {
          exws(extWorkspace) {
            env.PATH = "${tool 'M3'}/bin:${tool params.jdk_version}/bin:${env.PATH}"
            withEnv(["PATH=$env.PATH", "JAVA_HOME=${tool params.jdk_version}"]) {
              withCredentials([file(credentialsId: 'mavenSettings', variable: 'MVN_SETTINGS')]) {

                stage('OWASP Dependency Check') {
                  timeout(time: timeouts.owasp, unit: timeouts.unit) {
                    echo "in owasp"
                  }
                }
              }
            }
          }
        }
      }, mvn_deploy: {
        node() {
          exws(extWorkspace) {
            env.PATH = "${tool 'M3'}/bin:${tool params.jdk_version}/bin:${env.PATH}"
            withEnv(["PATH=$env.PATH", "JAVA_HOME=${tool params.jdk_version}"]) {
              withCredentials([file(credentialsId: 'mavenSettings', variable: 'MVN_SETTINGS')]) {

                stage('Deploy snapshot artifacts') {
                  timeout(time: timeouts.deploy, unit: timeouts.unit) {
                    //sh "mvn -X -B -s $MVN_SETTINGS -gs $MVN_SETTINGS $localRepoParam --threads 2 -DskipTests deploy"
                  }
                }
              }
            }
          }
        }
      }
    ) // end of parallel
 
      currentBuild.result = 'SUCCESS' // Important for the Stash notifier!
    } catch (error) {
      currentBuild.result = 'FAILED' // Important for the Stash notifier!
      throw error
    } finally {
      node() {
        step([$class: 'StashNotifier']) // Notify stash of the build result
      }
    }

    // we are here at the end of the pre-release phase which is executed upon every master commit.
    // The steps below are all release-related, i.e. manual input is required.

    stage('Release') {
      int[] versionDigits = parseReleaseVersionFromPom(pom)
      def nextGenVersion = increaseVersion(versionDigits, 0)
      def majorVersion = increaseVersion(versionDigits, 1)
      def minorVersion = increaseVersion(versionDigits, 2)
      def bugFixVersion = increaseVersion(versionDigits, 3)
      String releaseVersion = getReleaseVersionFromPom(pom)
      releaseVersion = removeSnapshot(releaseVersion)
      def nextVersion = ""

      def promote = false
      def whoPromoted = null
      def stageName = "Release: release version"
      stage(stageName) {
        try {
          timeout(time: timeouts.release_promote, unit: timeouts.unit) {
            def userInput = input(
                    id: 'userInput', message: 'Perform release, release version?', submitter: RELEASE_PROMOTERS,
                    submitterParameter:
                            'submitter', parameters: [
                    [$class: 'TextParameterDefinition', defaultValue: releaseVersion, description: 'Release version', name:
                            'release_version'],
            ])
            echo "$stageName, entered value: $userInput"
            releaseVersion = userInput.release_version
            whoPromoted = userInput.submitter
            promote = true
          }
        } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
          echo 'Shall not perform release'
          echo 'Reason: the required input wasn\'t given, which timed the operation out'
        }
      }
      stageName = "Release: snapshot version"
      stage(stageName) {
        versionDigits = parseReleaseVersion(releaseVersion)
        def nextGenSnapshot = increaseVersion(versionDigits, 0, true)
        def majorSnapshot = increaseVersion(versionDigits, 1, true)
        def minorSnapshot = increaseVersion(versionDigits, 2, true)
        def bugFixSnapshot = increaseVersion(versionDigits, 3, true)

        if (promote) {
          try {
            timeout(time: timeouts.release_promote, unit: timeouts.unit) {
              def userInput = input(
                      id: 'userInput', message: 'Perform release, next version?', submitter: RELEASE_PROMOTERS,
                      submitterParameter: 'submitter', parameters: [
                      [$class: 'TextParameterDefinition', defaultValue: minorSnapshot, description: 'Next shnapshot version',
                       name  : 'next_version'],

                      // String choicesSnapshot = "5.5.0.1-2-SNAPSHOT" + "\n" + "5.5.0.2-1-SNAPSHOT" + "\n"
                      // [$class: 'ChoiceParameterDefinition', choices: "$choicesSnapshot", name: 'next_version'],
              ])
              echo "$stageName, entered value: $userInput"
              promote = true
              nextVersion = userInput.next_version
              whoPromoted = userInput.submitter
            }
          } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
            echo 'Shall not perform release'
            echo 'Reason: the required input wasn\'t given, which timed the operation out'
            promote = false
          }
        }
      }
      if (promote) {
        node() {
          exws(extWorkspace) {
            sshagent(credentials: ['jenkins-ssh']) {
              env.PATH = "${tool 'M3'}/bin:${tool params.jdk_version}/bin:${env.PATH}"
              def releaseBranch = RELEASE_BRANCH_PREFIX + releaseVersion

              withEnv(["PATH=$env.PATH", "JAVA_HOME=${tool params.jdk_version}"]) {
                stage('Release: pre-release Cleanup') {
                  timeout(time: timeouts.pre_release, unit: timeouts.unit) {
                    deleteDir()
                    checkout scm
                  }
                }

                stage('Release: perform') {
                  withCredentials([file(credentialsId: 'mavenSettings', variable: 'MVN_SETTINGS')]) {
                    timeout(time: timeouts.release, unit: timeouts.unit) {
                      def tag = artifactId(pom) + '-' + releaseVersion
                      def promoterName = getMailAddress(whoPromoted)
                      def promoterMail = getUsername(whoPromoted)
                      // The checkout runs on a detached HEAD that makes the mvn release plugin unhappy
                      // So, create a temp release branch
                      sh "git checkout -b $releaseBranch"
                      sh "git config user.email \"$promoterName\" && git config user.name \"$promoterMail\""
                      sh "mvn -B -s $MVN_SETTINGS -gs $MVN_SETTINGS $localRepoParam clean"
                      sh "mvn -B -s $MVN_SETTINGS -gs $MVN_SETTINGS $localRepoParam release:prepare -DreleaseVersion=$releaseVersion -DdevelopmentVersion=$nextVersion -Dtag=$tag -Darguments=\"-DskipTests\""
                      sh "mvn -B -s $MVN_SETTINGS -gs $MVN_SETTINGS $localRepoParam release:perform -Darguments=\"-DskipTests\""
                    }
                  }
                }

                stage('Release: post cleanup') {
                  timeout(time: timeouts.post_release, unit: timeouts.unit) {
                    sh 'git config push.default simple'
                    // merge the release branch into the target branch and delete it
                    sh "git checkout $env.BRANCH_NAME"
                    sh 'git pull'
                    sh "git merge --ff $releaseBranch"
                    sh "git push origin $env.BRANCH_NAME"
                    sh "git push origin :$releaseBranch" // delete remotely by pushing 'nothing'
                    sh "git branch -d $releaseBranch"
                  }
                }
              }
            }
          }
        }
      } // end of release - both the promote buttons were pushed
    } // end of the overall release brace
  } // end of the whole pipeline
}
catch (error) {
  node() {
    currentBuild.result = 'FAILED' // Important for the Stash notifier!
    step([$class: 'StashNotifier']) // Notify stash of the build result
    // notify stash and mattermost
    buildStatus(currentBuild.result, NOTIFICATION_CHANNEL)
  }
  throw error
}
finally {
  // Clean the whole workspace that we allocated upfront
  node() {
    exws(extWorkspace) {
      cleanWs deleteDirs: true
    }
    // notify via email - see http://javadoc.jenkins.io/plugin/mailer/
    step([$class: 'Mailer', notifyEveryUnstableBuild: false, recipients: mailRecipients,  sendToIndividuals: true])
  }
}

// End of pipeline

// Utility functions for parsing POM files
// ---------------------------------------

def buildStatus(String buildStatus, String channel) {
  def subject = "**${buildStatus}**: ${env.JOB_NAME} [${env.BUILD_NUMBER}]"
  def summary = "${subject} [Open](${env.BUILD_URL}) _Notifier_"

  if (buildStatus == 'STARTED') {
    color = '#FFFF00'
  } else if (buildStatus == 'SUCCESS') {
    color = '#00FF00'
  } else if (buildStatus == 'AWAITING_INPUT') {
    color = '#0000FF'
  } else {
    color = '#FF0000'
  }

  // send notifications
  mattermostSend(channel: channel, color: color, message: summary)
  slackSend(channel: channel, color: color, message: summary)
}
/**
 * Parse the main pom.xml and retrieve the maven artifact Id.
 * @param pom the webtoolkit-mainline pom.xml as read from the file system.
 * @return the artifact as String
 */
@NonCPS
def artifactId(String pom) {
  def pomRoot = new XmlSlurper().parseText(pom)
  return pomRoot.artifactId.text()
}

/**
 * Parse the main pom.xml, retrieve the next release version and return it as String
 * @param pom the webtoolkit-mainline pom.xml as read from the file system.
 * @return the parsed String
 */
@NonCPS
def getReleaseVersionFromPom(String pom) {
  def pomRoot = new XmlSlurper().parseText(pom)
  return pomRoot.version.text()
}

/**
 * Parse the main pom.xml, retrieve the gwt version defined there (^= default value) and return it as String
 *
 * @param pom the content of the main pom.xml
 * @return the gwt version like defined as "gwt.version" in the pom.xml under the tag "<properties>"
 */
@NonCPS
def getGWTVersionFromPom(String pom) {
  def pomRoot = new XmlSlurper().parseText(pom)
  return pomRoot.properties."gwt.version".text()
}



/**
 * Is this build running with the most recent gwt version and Java 8?
 *
 * @param gwt the GWT version used for this build
 * @param jdk the JDK version used for this build
 * @return true if we have the relase config, false otherwise
 */
boolean isFullBuildWithJava8AndLatestGWT(String gwt, String jdk, String pomGWTVersion) {
  return gwt == pomGWTVersion && jdk ==  'JDK8'
}

/**
 * Parse the main pom.xml, retrieve the next release version and return the int array with 4 members
 * @param pom the webtoolkit-mainline pom.xml as read from the file system.
 * @return an array with the 4 integers, that are conform to the levigo coding scheme for a release version
 */
@NonCPS
def parseReleaseVersionFromPom(String pom) {
  return parseReleaseVersion(getReleaseVersionFromPom(pom))
}

/**
 * Take a version string like the snapshot version from a pom and remove the "-SNAPSHOT" postfix
 * @param version a levigo conform version
 * @return the version as String
 */
@NonCPS
def removeSnapshot(String version) {
  String release = version.replace("-SNAPSHOT", "")
  def split = release.split('\\.')
  if (split.length != 4) {
    throwIllegalArgument(version)
  }

  // try to parse every split String so an exception is thrown upon invalid integers.
  // don't try to parse the last digit though as we want to be able to use versions like 1.2.3.4-T1
  for (int i = 0; i < 3; i++) {
    Integer.parseInt(split[i])
  }
  return release
}

/**
 * Retrieve the next release version and return the int array with 4 members
 * @param release a string containing a version which is conform to the levigo scheme for versions
 * as read from the file system.
 * @return an array with the 4 integers, that are conform to the levigo coding scheme for a release version
 */
@NonCPS
def parseReleaseVersion(String release) {
  def split = release.split('\\.')
  def versionNumbers = new int[4]
  if (split.length == 4) {

    // remove the -snapshot if there is any
    split[3] = split[3] - "-SNAPSHOT"

    versionNumbers[0] = Integer.parseInt(split[0])
    versionNumbers[1] = Integer.parseInt(split[1])
    versionNumbers[2] = Integer.parseInt(split[2])
    try {
      versionNumbers[3] = Integer.parseInt(split[3])
    }
    // make an exception here to enable stuff like 5.6.7.8-TEST-23
    catch (NumberFormatException e) {
      versionNumbers[3] = 0
    }
  } else {
    echo("Parsing a release version failed, the malformed string is \"$release\"")
    throwIllegalArgument(release)
  }
  return versionNumbers
}

/**
 * Throw an {@link IllegalArgumentException} if the release version isn't conform to the levigo scheme
 * @param release the non-conform release version
 */
@NonCPS
def throwIllegalArgument(String release) {
  throw new IllegalArgumentException(
          "The jadice version scheme is A.B.C.D(-SNAPSHOT)?, while the input was $release")
}

/**
 * Calculate the next valid version, depending on which digit shall be increased
 *
 * @param versionDigits the 4 digits that make a up a levigo conform version
 * @param increaseDigit which of the 4 digits shall be increased?
 * @return the next version as String
 */
@NonCPS
increaseVersion(int[] versionDigits, int increaseDigit) {
  return increaseVersion(versionDigits, increaseDigit, false)
}

/**
 * Calculate the next valid version, depending on which digit shall be increased
 *
 * @param versionDigits the 4 digits that make a up a levigo conform version
 * @param increaseDigit which of the 4 digits shall be increased?
 * @param isSetSnapShot shall the next version be a snapshot version? if not, skip the "-SNAPSHOT" postfix
 * @return the next version as String
 */
@NonCPS
increaseVersion(int[] versionDigits, int increaseDigit, boolean isSetSnapShot) {
  int[] digits = versionDigits.clone()

  digits[increaseDigit] = digits[increaseDigit] + 1
  for (int i = increaseDigit + 1; i < 4; i++) {
    digits[i] = 0
  }

  String returnValue = ""
  for (int i = 0; i < 3; i++) {
    returnValue += digits[i] + "."
  }
  returnValue += digits[3]

  if (isSetSnapShot)
    returnValue += "-SNAPSHOT"

  return returnValue
}

// Utility functions for jenkins user management
// ---------------------------------------------

def getUsername(String userId) {
  def fallback = 'Build Server'
  if (!userId) {
    return fallback
  }
  def user = User.getById(userId, false)
  return user ? user.fullName : fallback
}

def getMailAddress(String userId) {
  def fallback = 'donotreply@levigo.de'
  if (!userId) {
    return fallback
  }
  def user = User.getById(userId, false)
  return user ? user.getProperty(hudson.tasks.Mailer.UserProperty.class).address : fallback
}
