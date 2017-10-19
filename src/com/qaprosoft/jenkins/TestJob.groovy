package com.qaprosoft.jenkins

@Grab('org.yaml:snakeyaml:1.18')
import org.yaml.snakeyaml.*
import org.yaml.snakeyaml.constructor.*

import static java.util.UUID.randomUUID


def runJob() {
    def jobParameters = setJobType("${suite}")
    def mobileGoals = ""

    node(jobParameters.get("node")) {
        timestamps {
            this.prepare(jobParameters)

            this.repoClone()

            this.getResources()

	    if (params["device"] != null && !params["device"].isEmpty() && !params["device"].equals("NULL")) {
                mobileGoals = this.setupForMobile("${device}", jobParameters)
            }

            this.runTests(jobParameters, mobileGoals)

            this.reportingResults()

            this.cleanWorkSpace()
        }
    }
}

def setJobType(String suiteInfo) {

    switch(suiteInfo) {
        case ~/^(?!.*web).*api.*$/:
            println "Suite Type: API";
            return setJobParameters("env,url", "API", "master")
        case ~/^.*web.*$/:
            println "Suite Type: Web";
            return setJobParameters("env,url,browser,browser_version", "*", "spot-fleet")
        case ~/^.*android.*$/:
            println "Suite Type: Android";
            return setJobParameters("app_version,mobile_device_name,mobile_device_udid,mobile_platform_name,mobile_platform_version", "ANDROID", "android")
        case ~/^.*ios.*$/:
            println "Suite Type: iOS";
            //TODO: Need to improve this to be able to handle where emulator vs. physical tests should be run.
            return setJobParameters("app_version,mobile_device_name,mobile_device_udid,mobile_platform_name,mobile_platform_version", "ios", "ios")
        default:
            println "Suite Type: Default";
            return setJobParameters("env,url", "*", "master")
    }
}

def setJobParameters(String testFields, String platform, String nodeType) {
    def jobProperties = [:]
    jobProperties.put("testField", testFields)
    jobProperties.put("platform", platform)
    jobProperties.put("node", nodeType)
    return jobProperties
}

def prepare(Map jobParameters) {
    stage('Preparation') {
        currentBuild.displayName = "#${BUILD_NUMBER}|${suite}|${env.env}"
	if (!isParamEmpty(params["carina-core_version"]))) {
	    currentBuild.displayName += "|" + params["carina-core_version"]) 
	}
	if (!isParamEmpty(params["device"])) {
	    currentBuild.displayName += "|${device}"
	}
	if (!isParamEmpty(params["browser"])) {
	    currentBuild.displayName += "|${browser}"
	}
	if (!isParamEmpty(params["browser_version"])) {
	    currentBuild.displayName += "|${browser_version}"
	}
	
        currentBuild.description = "${suite}"
    }
}

def repoClone() {
    stage('Checkout GitHub Repository') {
        git branch: '${branch}', credentialsId: 'vdelendik', url: '${repository}', changelog: false, poll: false, shallow: true
    }
}

def getResources() {
    stage("Download Resources") {
        if (isUnix()) {
            sh "'mvn' -f pom.xml clean process-resources process-test-resources"
        } else {
            bat(/"mvn" -f pom.xml clean process-resources process-test-resources/)
        }
    }
}

def setupForMobile(String devicePattern, Map jobParameters) {

    def goalMap = [:]

    stage("Mobile Preparation") {
        if (jobParameters.get("platform").toString().equalsIgnoreCase("android")) {
            goalMap = setupGoalsForAndroid(goalMap)
        } else {
            goalMap = setupGoalsForiOS(goalMap)
        }
       	echo "DEVICE: " +  devicePattern


        if (!devicePattern.equalsIgnoreCase("all")) {
            goalMap.put("capabilities.deviceName", devicePattern)
	}

	//TODO: remove after resolving issues with old mobile capabilities generator
	goalMap.put("capabilities.platformName", jobParameters.get("platform").toString().toUpperCase())

        goalMap.put("driver_type", "mobile_grid")

        goalMap.put("capabilities.newCommandTimeout", "180")

        goalMap.put("mobile_appium_restart", "false")
        goalMap.put("mobile_app_uninstall", "false")
        goalMap.put("mobile_app_install", "false")


        goalMap.put("retry_count", "${retry_count}")
        goalMap.put("thread_count", "${thread_count}")
        goalMap.put("retry_interval", "1000")
        goalMap.put("implicit_timeout", "30")
        goalMap.put("explicit_timeout", "60")
        goalMap.put("java.awt.headless", "true")

    }
    return buildOutGoals(goalMap)
}

def setupGoalsForAndroid(Map<String, String> goalMap) {

    echo "ENV: " +  params["env"]

    goalMap.put("mobile_app_clear_cache", "true")

    goalMap.put("capabilities.platform", "ANDROID")
    goalMap.put("capabilities.platformName", "ANDROID")
    goalMap.put("capabilities.deviceName", "*")

    goalMap.put("capabilities.appPackage", "")
    goalMap.put("capabilities.appActivity", "")

    goalMap.put("capabilities.autoGrantPermissions", "true")
    goalMap.put("capabilities.noSign", "true")
    goalMap.put("capabilities.STF_ENABLED", "true")

    return goalMap
}


def setupGoalsForiOS(Map<String, String> goalMap) {


    goalMap.put("capabilities.platform", "IOS")
    goalMap.put("capabilities.platformName", "IOS")
    goalMap.put("capabilities.deviceName", "*")

    goalMap.put("capabilities.appPackage", "")
    goalMap.put("capabilities.appActivity", "")

    goalMap.put("capabilities.noSign", "false")
    goalMap.put("capabilities.autoGrantPermissions", "false")
    goalMap.put("capabilities.autoAcceptAlerts", "true")

    goalMap.put("capabilities.STF_ENABLED", "false")

    // remove after fixing
    goalMap.put("capabilities.automationName", "XCUITest")

    return goalMap
}


def buildOutGoals(Map<String, String> goalMap) {
    def goals = ""

    goalMap.each { k, v -> goals = goals + " -D${k}=${v}"}

    return goals
}

def runTests(Map jobParameters, String mobileGoals) {
    stage('Run Test Suite') {
        def goalMap = [:]

	uuid = "${ci_run_id}"
	echo "uuid: " + uuid
        if (uuid.isEmpty()) {
            uuid = randomUUID() as String
        }
	echo "uuid: " + uuid

        def zafiraEnabled = "false"
        def regressionVersionNumber = new Date().format('yyMMddhhmm')
        if ("${DEFAULT_BASE_MAVEN_GOALS}".contains("zafira_enabled=true")) {
            zafiraEnabled = "true"
        }

        if ("${develop}".contains("true")) {
            echo "Develop Flag has been Set, disabling interaction with Zafira Reporting."
            zafiraEnabled = "false"
        }

        goalMap.put("env", params["env"])

	if (params["browser"] != null && !params["browser"].isEmpty()) {
            goalMap.put("browser", params["browser"])
	}

	if (!isParamEmpty(params["auto_screenshot"])) {
            goalMap.put("auto_screenshot", params["auto_screenshot"])
	}

	if (!isParamEmpty(params["keep_all_screenshots"])) {
            goalMap.put("keep_all_screenshots", params["keep_all_screenshots"])
	}

	goalMap.put("zafira_enabled", "${zafiraEnabled}")
        goalMap.put("ci_run_id", "${uuid}")
        goalMap.put("ci_url", "$JOB_URL")
        goalMap.put("ci_build", "$BUILD_NUMBER")
        goalMap.put("platform", jobParameters.get("platform"))

        def mvnBaseGoals = "${DEFAULT_BASE_MAVEN_GOALS} ${overrideFields}" + buildOutGoals(goalMap) + mobileGoals

        if (isUnix()) {
            suiteNameForUnix = "${suite}".replace("\\", "/")
            echo "Suite for Unix: ${suiteNameForUnix}"
            sh "'mvn' ${mvnBaseGoals} -Dsuite=${suiteNameForUnix} -Dzafira_report_folder=./reports/qa -Dreport_url=$JOB_URL$BUILD_NUMBER/eTAF_Report"
        } else {
            suiteNameForWindows = "${suite}".replace("/", "\\")
            echo "Suite for Windows: ${suiteNameForWindows}"
            bat(/"mvn" ${mvnBaseGoals} -Dsuite=${suiteNameForWindows} -Dzafira_report_folder=.\reports\qa -Dreport_url=$JOB_URL$BUILD_NUMBER\eTAF_Report/)
        }

        this.setTestResults()
    }
}

def setTestResults() {
    //Need to do a forced failure here in case the report doesn't have PASSED or PASSED KNOWN ISSUES in it.
    checkReport = readFile("./reports/qa/emailable-report.html")
    if (!checkReport.contains("PASSED:") && !checkReport.contains("PASSED (known issues):") && !checkReport.contains("SKIP_ALL:")) {
        echo "Unable to Find (Passed) or (Passed Known Issues) within the eTAF Report."
        currentBuild.result = 'FAILURE'
    } else if (checkReport.contains("SKIP_ALL:")) {
        currentBuild.result = 'UNSTABLE'
    }
}

def reportingResults() {
    stage('Results') {
        if (fileExists("./reports/qa/zafira-report.html")) {
            echo "Zafira Report File Found, Publishing File"
            publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: true, reportDir: './reports/qa/', reportFiles: 'zafira-report.html', reportName: 'eTAF_Report'])
        } else {
            echo "Zafira Report File Not Found, Publishing E-Mail File"
            publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: true, reportDir: './reports/qa/', reportFiles: 'emailable-report.html', reportName: 'eTAF_Report'])

        }
        publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: true, reportDir: './target/surefire-reports/', reportFiles: 'index.html', reportName: 'Full TestNG HTML Report'])
        publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: true, reportDir: './target/surefire-reports/', reportFiles: 'emailable-report.html', reportName: 'TestNG Summary HTML Report'])
    }
}

def cleanWorkSpace() {
    stage('Wipe out Workspace') {
        deleteDir()
    }
}

def isParamEmpty(String value) {
    if (value == null || value.isEmpty() || value.equals("NULL")) {
	return true
    } else {
	return false
    }
}

