// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1LocalObjectReference;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1SecretReference;
import oracle.weblogic.domain.AdminServer;
import oracle.weblogic.domain.AdminService;
import oracle.weblogic.domain.Channel;
import oracle.weblogic.domain.Cluster;
import oracle.weblogic.domain.Configuration;
import oracle.weblogic.domain.Domain;
import oracle.weblogic.domain.DomainSpec;
import oracle.weblogic.domain.Model;
import oracle.weblogic.domain.ServerPod;
import oracle.weblogic.kubernetes.actions.impl.LoggingExporterParams;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import oracle.weblogic.kubernetes.utils.ExecResult;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static oracle.weblogic.kubernetes.TestConstants.COPY_WLS_LOGGING_EXPORTER_FILE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_API_VERSION;
import static oracle.weblogic.kubernetes.TestConstants.ELASTICSEARCH_HOST;
import static oracle.weblogic.kubernetes.TestConstants.ELASTICSEARCH_HTTP_PORT;
import static oracle.weblogic.kubernetes.TestConstants.KIBANA_INDEX_KEY;
import static oracle.weblogic.kubernetes.TestConstants.LOGSTASH_INDEX_KEY;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_APP_NAME;
import static oracle.weblogic.kubernetes.TestConstants.OPERATOR_RELEASE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.REPO_SECRET_NAME;
import static oracle.weblogic.kubernetes.TestConstants.SNAKE_YAML_JAR_NAME;
import static oracle.weblogic.kubernetes.TestConstants.WEBLOGIC_INDEX_KEY;
import static oracle.weblogic.kubernetes.TestConstants.WLS_LOGGING_EXPORTER_JAR_NAME;
import static oracle.weblogic.kubernetes.TestConstants.WLS_LOGGING_EXPORTER_YAML_FILE_NAME;
import static oracle.weblogic.kubernetes.actions.ActionConstants.DOWNLOAD_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.RESOURCE_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WORK_DIR;
import static oracle.weblogic.kubernetes.actions.TestActions.deleteDomainCustomResource;
import static oracle.weblogic.kubernetes.actions.TestActions.execCommand;
import static oracle.weblogic.kubernetes.actions.TestActions.getOperatorPodName;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkPodReady;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkServiceExists;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createDockerRegistrySecret;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createDomainAndVerify;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createMiiImageAndVerify;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.dockerLoginAndPushImageToRegistry;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.installAndVerifyElasticsearch;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.installAndVerifyKibana;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.installAndVerifyOperator;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.installAndVerifyWlsLoggingExporter;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.uninstallAndVerifyElasticsearch;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.uninstallAndVerifyKibana;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.verifyLoggingExporterReady;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.awaitility.Awaitility.with;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Test to create a WebLogic domain with Coherence, build the Coherence proxy client program
// which load and verify the cache.
@DisplayName("Test to create a WebLogic domain with Coherence and verify the use of Coherence cache service")
@IntegrationTest
class ItWlsLoggingExporterSample {

  // constants for creating domain image using model in image
  private static final String WLS_LOGGING_MODEL_FILE = "model.wlslogging.yaml";
  private static final String WLS_LOGGING_IMAGE_NAME = "wls-logging-image";
  private static final String wlsLoggingExporterYamlFileLoc = RESOURCE_DIR + "/loggingexporter";
  private static final String wlsLoggingExporterArchiveLoc = DOWNLOAD_DIR + "/loggingExporterArchiveDir";
  private static final int maxIterationsPod = 10;

  private static String k8sExecCmdPrefix;
  private static Map<String, String> testVarMap;

  private static String domainUid = "elk-domain1";
  private static String clusterName = "cluster-1";
  private static String adminServerPodName = domainUid + "-admin-server";
  private static String managedServerPrefix = domainUid + "-managed-server";
  private static String managedServerFilter = managedServerPrefix + "1";
  private static int replicaCount = 2;

  private static String opNamespace = null;
  private static String domainNamespace = null;
  private static ConditionFactory withStandardRetryPolicy = null;

  private static LoggingExporterParams elasticsearchParams = null;
  private static LoggingExporterParams kibanaParams = null;

  private static LoggingFacade logger = null;

  /**
   * Install Operator.
   *
   * @param namespaces list of namespaces created by the IntegrationTestWatcher by the
   *                   JUnit engine parameter resolution mechanism
   */
  @BeforeAll
  public static void init(@Namespaces(2) List<String> namespaces) {
    logger = getLogger();
    // create standard, reusable retry/backoff policy
    withStandardRetryPolicy = with().pollDelay(2, SECONDS)
      .and().with().pollInterval(10, SECONDS)
      .atMost(5, MINUTES).await();

    // get a new unique opNamespace
    logger.info("Assigning a unique namespace for Operator");
    assertNotNull(namespaces.get(0), "Namespace list is null");
    opNamespace = namespaces.get(0);

    // get a new unique domainNamespace
    logger.info("Assigning a unique namespace for Domain");
    assertNotNull(namespaces.get(1), "Namespace list is null");
    domainNamespace = namespaces.get(1);

    // install and verify ELK Stack
    logger.info("Instull and verify Elasticsearch");
    elasticsearchParams = assertDoesNotThrow(() -> installAndVerifyElasticsearch(),
            String.format("Failed to install Elasticsearch"));
    assertTrue(elasticsearchParams != null, "Failed to install Elasticsearch");

    // install and verify Kibana
    logger.info("Instull and verify Kibana");
    kibanaParams = assertDoesNotThrow(() -> installAndVerifyKibana(),
        String.format("Failed to install Kibana"));
    assertTrue(kibanaParams != null, "Failed to install Kibana");

    // install and verify operator
    installAndVerifyOperator(opNamespace, opNamespace + "-sa", false, 0, true, domainNamespace);

    installAndVerifyWlsLoggingExporter(managedServerFilter,
        wlsLoggingExporterYamlFileLoc, wlsLoggingExporterArchiveLoc);

    // create and verify WebLogic domain image using model in image with model files
    String imageName = createAndVerifyDomainImage();

    // create and verify one cluster domain
    logger.info("Create domain and verify that it's running");
    createAndVerifyDomain(imageName);

    testVarMap = new HashMap<String, String>();

    StringBuffer elasticSearchUrlBuff =
        new StringBuffer("curl http://")
            .append(ELASTICSEARCH_HOST)
            .append(":")
            .append(ELASTICSEARCH_HTTP_PORT);
    k8sExecCmdPrefix = elasticSearchUrlBuff.toString();
    logger.info("elasticSearch URL {0}", k8sExecCmdPrefix);

    // Verify that Elastic Stack is ready to use
    testVarMap = verifyLoggingExporterReady(opNamespace, null, LOGSTASH_INDEX_KEY);
    Map<String, String> kibanaMap = verifyLoggingExporterReady(opNamespace, null, KIBANA_INDEX_KEY);

    // merge testVarMap and kibanaMap
    testVarMap.putAll(kibanaMap);
  }

  // This method is needed in this test class, since the cleanup util
  // won't cleanup the images.
  @AfterAll
  void tearDown() {
    // uninstall ELK Stack
    if (elasticsearchParams != null) {
      logger.info("Uninstall Elasticsearch pod");
      assertDoesNotThrow(() -> uninstallAndVerifyElasticsearch(elasticsearchParams),
          "uninstallAndVerifyElasticsearch failed with ApiException");
    }

    if (kibanaParams != null) {
      logger.info("Uninstall Elasticsearch pod");
      assertDoesNotThrow(() -> uninstallAndVerifyKibana(kibanaParams),
          "uninstallAndVerifyKibana failed with ApiException");
    }

    // delete domain custom resource
    logger.info("Delete domain custom resource in namespace {0}", domainNamespace);
    assertDoesNotThrow(() -> deleteDomainCustomResource(domainUid, domainNamespace),
        "deleteDomainCustomResource failed with ApiException");
    logger.info("Deleted Domain Custom Resource " + domainUid + " from " + domainNamespace);
  }

  /**
   * Use Elasticsearch Count API to query logs of level=INFO. Verify that total number of logs
   * for level=INFO is not zero and failed count is zero.
   *
   */
  @Test
  @DisplayName("Create the Voyager ingress and test Voyager load balancing with host name-based routing")
  public void testWlsLoggingExporter() throws Exception {
    Map<String, String> wlsMap = verifyLoggingExporterReady(opNamespace, null, WEBLOGIC_INDEX_KEY);
    // merge testVarMap and wlsMap
    testVarMap.putAll(wlsMap);

    // Verify that hits of log level = Notice are not empty
    String regex = ".*took\":(\\d+),.*hits\":\\{(.+)\\}";
    String queryCriteria = "/_search?q=level:Notice";
    verifySearchResults(queryCriteria, regex, WEBLOGIC_INDEX_KEY, false);
    // Verify that hits of loggerName = WebLogicServer are not empty
    queryCriteria = "/_search?q=loggerName:WebLogicServer";
    verifySearchResults(queryCriteria, regex, WEBLOGIC_INDEX_KEY, false);
    // Verify that hits of _type:doc are not empty
    queryCriteria = "/_search?q=_type:doc";
    verifySearchResults(queryCriteria, regex, WEBLOGIC_INDEX_KEY, false);
    // Verify that serverName:managed-server1 is filtered out
    // by checking the count of logs from serverName:managed-server1 is zero and no failures
    // e.g. when running the query:
    // curl -X GET http://elasticsearch.default.svc.cluster.local:9200/wls/_count?q=serverName:managed-server1
    // Expected return result is:
    // {"count":0,"_shards":{"total":5,"successful":5,"skipped":0,"failed":0}}
    regex = ".*count\":(\\d+),.*failed\":(\\d+)";
    queryCriteria = "/_count?q=serverName:" + managedServerFilter;
    verifySearchResults(queryCriteria, regex, WEBLOGIC_INDEX_KEY, true, "notExist");

    logger.info("SUCCESS - ");
  }

  private static String createAndVerifyDomainImage() {
    // create image with model files
    logger.info("Create image with model file and verify");
    String additionalBuildCommands = WORK_DIR + "/" + COPY_WLS_LOGGING_EXPORTER_FILE_NAME;

    StringBuffer additionalBuildFilesVarargsBuff = new StringBuffer()
        .append(WORK_DIR)
        .append("/")
        .append(WLS_LOGGING_EXPORTER_YAML_FILE_NAME)
        .append(",")
        .append(wlsLoggingExporterArchiveLoc)
        .append("/")
        .append(WLS_LOGGING_EXPORTER_JAR_NAME)
        .append(",")
        .append(wlsLoggingExporterArchiveLoc)
        .append("/")
        .append(SNAKE_YAML_JAR_NAME);

    String miiImage =
        createMiiImageAndVerify(WLS_LOGGING_IMAGE_NAME, WLS_LOGGING_MODEL_FILE, MII_BASIC_APP_NAME,
          additionalBuildCommands, additionalBuildFilesVarargsBuff.toString());

    // docker login and push image to docker registry if necessary
    dockerLoginAndPushImageToRegistry(miiImage);

    // create docker registry secret to pull the image from registry
    logger.info("Create docker registry secret in namespace {0}", domainNamespace);
    assertDoesNotThrow(() -> createDockerRegistrySecret(domainNamespace),
        String.format("create Docker Registry Secret failed for %s", REPO_SECRET_NAME));

    return miiImage;
  }

  private static void createAndVerifyDomain(String miiImage) {
    // create secret for admin credentials
    logger.info("Create secret for admin credentials");
    String adminSecretName = "weblogic-credentials";
    assertDoesNotThrow(() -> createSecretWithUsernamePassword(adminSecretName, domainNamespace,
        "weblogic", "welcome1"),
        String.format("create secret for admin credentials failed for %s", adminSecretName));

    // create encryption secret
    logger.info("Create encryption secret");
    String encryptionSecretName = "encryptionsecret";
    assertDoesNotThrow(() -> createSecretWithUsernamePassword(encryptionSecretName, domainNamespace,
        "weblogicenc", "weblogicenc"),
        String.format("create encryption secret failed for %s", encryptionSecretName));

    // create domain and verify
    logger.info("Create model in image domain {0} in namespace {1} using docker image {2}",
        domainUid, domainNamespace, miiImage);
    createDomainCrAndVerify(adminSecretName, REPO_SECRET_NAME, encryptionSecretName, miiImage);

    // check that admin service exists in the domain namespace
    logger.info("Checking that admin service {0} exists in namespace {1}",
        adminServerPodName, domainNamespace);
    checkServiceExists(adminServerPodName, domainNamespace);

    // check that admin server pod is ready
    logger.info("Checking that admin server pod {0} is ready in namespace {1}",
        adminServerPodName, domainNamespace);
    checkPodReady(adminServerPodName, domainUid, domainNamespace);

    // check for managed server pods existence in the domain namespace
    for (int i = 1; i <= replicaCount; i++) {
      String managedServerPodName = managedServerPrefix + i;

      // check that the managed server service exists in the domain namespace
      logger.info("Checking that managed server service {0} exists in namespace {1}",
          managedServerPodName, domainNamespace);
      checkServiceExists(managedServerPodName, domainNamespace);

      // check that the managed server pod is ready
      logger.info("Checking that managed server pod {0} is ready in namespace {1}",
          managedServerPodName, domainNamespace);
      checkPodReady(managedServerPodName, domainUid, domainNamespace);
    }
  }

  private static void createDomainCrAndVerify(String adminSecretName,
                                              String repoSecretName,
                                              String encryptionSecretName,
                                              String miiImage) {
    // create the domain CR
    Domain domain = new Domain()
        .apiVersion(DOMAIN_API_VERSION)
        .kind("Domain")
        .metadata(new V1ObjectMeta()
            .name(domainUid)
            .namespace(domainNamespace))
        .spec(new DomainSpec()
            .domainUid(domainUid)
            .domainHomeSourceType("FromModel")
            .image(miiImage)
            .addImagePullSecretsItem(new V1LocalObjectReference()
                .name(repoSecretName))
            .webLogicCredentialsSecret(new V1SecretReference()
                .name(adminSecretName)
                .namespace(domainNamespace))
            .includeServerOutInPodLog(true)
            .serverStartPolicy("IF_NEEDED")
            .serverPod(new ServerPod()
                .addEnvItem(new V1EnvVar()
                    .name("JAVA_OPTIONS")
                    .value("-Dweblogic.StdoutDebugEnabled=false"))
                .addEnvItem(new V1EnvVar()
                    .name("USER_MEM_ARGS")
                    .value("-Djava.security.egd=file:/dev/./urandom ")))
            .adminServer(new AdminServer()
                .serverStartState("RUNNING")
                    .adminService(new AdminService()
                        .addChannelsItem(new Channel()
                            .channelName("default")
                            .nodePort(0))))
            .addClustersItem(new Cluster()
                .clusterName(clusterName)
                .replicas(replicaCount)
                .serverStartState("RUNNING"))
            .configuration(new Configuration()
                .model(new Model()
                    .domainType("WLS")
                    .runtimeEncryptionSecret(encryptionSecretName))
                .introspectorJobActiveDeadlineSeconds(300L)));

    // create domain using model in image
    logger.info("Create model in image domain {0} in namespace {1} using docker image {2}",
        domainUid, domainNamespace, miiImage);
    createDomainAndVerify(domain, domainNamespace);
  }

  private void verifySearchResults(String queryCriteria, String regex,
                                   String index, boolean checkCount, String... args) {
    String checkExist = (args.length == 0) ? "" : args[0];
    int count = -1;
    int failedCount = -1;
    String hits = "";
    String results = null;
    int i = 0;
    while (i < maxIterationsPod) {
      results = execSearchQuery(queryCriteria, index);
      Pattern pattern = Pattern.compile(regex);
      Matcher matcher = pattern.matcher(results);
      if (matcher.find()) {
        count = Integer.parseInt(matcher.group(1));
        if (checkCount) {
          failedCount = Integer.parseInt(matcher.group(2));
        } else {
          hits = matcher.group(2);
        }

        break;
      }

      logger.info("Logs are not pushed to ELK Stack Ite [{0}/{1}], sleeping {2} seconds more",
          i, maxIterationsPod, maxIterationsPod);

      try {
        Thread.sleep(maxIterationsPod * 1000);
      } catch (InterruptedException ex) {
        //ignore
      }

      i++;
    }

    logger.info("====queryCriteria: " + queryCriteria);
    logger.info("====regex: " + regex);
    logger.info("====index: " + index);
    logger.info("====checkCount: " + checkCount);
    logger.info("====checkExist: " + checkExist);
    logger.info("Total count of logs: " + count);
    if (!checkExist.equalsIgnoreCase("notExist")) {
      assertTrue(kibanaParams != null, "Failed to install Kibana");
      assertTrue(count > 0, "Total count of logs should be more than 0!");
      if (checkCount) {
        assertTrue(failedCount == 0, "Total failed count should be 0!");
        logger.info("Total failed count: " + failedCount);
      } else {
        assertFalse(hits.isEmpty(), "Total hits of search is empty!");
      }
    } else {
      assertTrue(count == 0, "Total count of logs should be zero!");
    }
  }

  private String execSearchQuery(String queryCriteria, String index) {
    String operatorPodName = assertDoesNotThrow(
        () -> getOperatorPodName(OPERATOR_RELEASE_NAME, opNamespace));
    assertTrue(operatorPodName != null && !operatorPodName.isEmpty(), "Failed to get Operator pad name");
    logger.info("Operator pod name " + operatorPodName);

    int waittime = maxIterationsPod / 2;
    String indexName = (String) testVarMap.get(index);
    StringBuffer curlOptions = new StringBuffer(" --connect-timeout " + waittime)
        .append(" --max-time " + waittime)
        .append(" -X GET ");
    StringBuffer k8sExecCmdPrefixBuff = new StringBuffer(k8sExecCmdPrefix);
    int offset = k8sExecCmdPrefixBuff.indexOf("http");
    k8sExecCmdPrefixBuff.insert(offset, curlOptions);
    String cmd = k8sExecCmdPrefixBuff
        .append("/")
        .append(indexName)
        .append(queryCriteria)
        .toString();
    logger.info("Exec command {0} in Operator pod {1}", cmd, operatorPodName);

    ExecResult execResult = assertDoesNotThrow(
        () -> execCommand(opNamespace, operatorPodName, null, true,
            "/bin/sh", "-c", cmd));
    assertNotNull(execResult, "curl command returns null");
    logger.info("Search query returns " + execResult.stdout());

    return execResult.stdout();
  }
}