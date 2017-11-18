package com.qaprosoft.jenkins

def runPipeline() {
    node('master') {
        git(url: 'https://github.com/qaprosoft/carina-demo.git', credentialsId: '', branch: 'master' )

        def listPipelines = []
        def folderName = "Automation"
        def files = findFiles(glob: 'src/test/resources/testng_suites/**/*.xml')
    
        if(files.length > 0) {
            println "Number of Test Suites to Scan Through: " + files.length
            for (int i = 0; i < files.length; i++) {
                println "analyzing " + files[i].path
                parsePipeline(readFile(files[i].path), listPipelines)
            }

            println "Finished Dynamic Mapping: " + listPipelines
            def sortedPipeline = sortPipelineList(listPipelines)
            println "Finished Dynamic Mapping Sorted Order: " + sortedPipeline

            this.executeStages(folderName, sortedPipeline)
        } else {
            println "No Test Suites Found to Scan..."
        }
    }
}

def parsePipeline(String file, List listPipelines) {
    def jobName = retrieveRawValues(file, "jenkinsJobName")
    println "jobName: " + jobName
    def pipelineInfo = retrieveRawValues(file, "jenkinsRegressionPipeline")
    println "pipelineInfo: " + pipelineInfo
    def priorityInfo = retrieveRawValues(file, "jenkinsJobExecutionOrder")
    println "priorityInfo: " + priorityInfo

    def emailList = getInfo(retrieveRawValues(file, "jenkinsEmail"))
    if (!"${email_list}".isEmpty()) {
        emailList = "${email_list}"
    }
    println "emailList: " + emailList

    def overrideFields = getInfo(retrieveRawValues(file, "overrideFields"))
    def env = params["env"]

    def retryCount = params["retry_count"]

    if (!pipelineInfo.contains("null")) {
        for (def pipeName : getInfo(pipelineInfo).split(",")) {
            if ("${JOB_BASE_NAME}".equalsIgnoreCase(pipeName)) {
                println "adding pipeline: " + pipeName
                listPipelines.add(mapObject(pipeName, jobName, env, retryCount, priorityInfo, emailList, overrideFields))
            }
        }
    }
}

def mapObject(String pipeName, String jobName, String envName, String retryCount, String priorityNum, String emailList, String overrideFields) {
    def pipelineMap = [:]
    pipelineMap.put("name", pipeName)
    pipelineMap.put("jobName", getInfo(jobName))
    pipelineMap.put("environment", envName)
    pipelineMap.put("retryCount", retryCount)
    pipelineMap.put("priority", getInfo(priorityNum))
    pipelineMap.put("emailList", emailList)
    pipelineMap.put("overrideFields", overrideFields)

    return pipelineMap
}

def retrieveRawValues(String file, String parameter) {
    def splitFile = ""
    if (file.length() > 0) {
        splitFile = file.split("<")
    }

    return splitFile.find { it.toString().contains(parameter)}.toString()
}

def getInfo(String line) {
    //TODO: find a way to get value easier because it doesn't work for the case with spaces at the env of tag
    def valueStr = "value=\""
    def beginValue = line.indexOf(valueStr)
    def endValue = line.indexOf("\"/>")

    if (beginValue > 0 && endValue > 0) {
        retrievedValues = line.substring(beginValue + valueStr.toString().size(), endValue)
        return retrievedValues
    }
    return ""
}

def buildOutStage(String folderName, Map entry) {
    stage(String.format("Stage: %s Environment: %s", entry.get("jobName"), entry.get("environment"))) {
        println "Dynamic Stage Created For: " + entry.get("jobName")
        println "Checking EmailList: " + entry.get("emailList")
        println "Checking Custom Fields: " + entry.get('overrideFields')

        def customFields = entry.get("overrideFields")
        def overrideField = ""

        if (customFields.toString().length() > 0 && !customFields.toString().contains("null")) {
            for (String customField : customFields.toString().split(",")) {
                overrideField = overrideField + "-D" + customField + "=" + this."$customField"
            }
        }

        build job: folderName + "/" + entry.get("jobName"),
            propagate: false,
                parameters: [
                        string(name: 'env', value: entry.get("environment")),
                        string(name: 'email_list', value: entry.get("emailList")),
                        string(name: 'retry_count', value: entry.get("retryCount")),
                        string(name: 'overrideFields', value: "${overrideField}".toString())
                ]
    }
}

def buildOutStages(String folderName, Map entry) {
    return {
        buildOutStage(folderName, entry)
    }
}

def executeStages(String folderName, List sortedPipeline) {
    def mappedStages = [:]

    boolean parallelMode = true

    for (Map entry : sortedPipeline) {
        if (!entry.get("priority").toString().contains("null") && entry.get("priority").toString().length() > 0 && parallelMode) {
            parallelMode = false
        }
        if (parallelMode) {
            mappedStages[String.format("Stage: %s Environment: %s", entry.get("jobName"), entry.get("environment"))] = buildOutStages(folderName, entry)
        } else {
            buildOutStage(folderName, entry)
        }
    }
    if (parallelMode) {
        parallel mappedStages
    }
}

@NonCPS
def sortPipelineList(List pipelineList) {
    return pipelineList.sort { map1, map2 -> !map1.priority ? !map2.priority ? 0 : 1 : !map2.priority ? -1 : map1.priority.toInteger() <=> map2.priority.toInteger() }
}


