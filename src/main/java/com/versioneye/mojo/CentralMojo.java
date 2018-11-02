package com.versioneye.mojo;

import com.versioneye.domain.Artefact;
import com.versioneye.maven.MavenIndexer;
import com.versioneye.service.RabbitMqService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.context.IndexUtils;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import com.versioneye.domain.MavenRepository;
import com.versioneye.service.ProductService;

import javax.jms.*;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.*;

/**
 * Fetches the index from the maven central repository and walks through the index
 * to update the database.
 */
@Mojo( name = "central", defaultPhase = LifecyclePhase.PROCESS_SOURCES )
public class CentralMojo extends SuperMojo {

    static final Logger logger = LogManager.getLogger(CentralMojo.class.getName());

    protected ProductService productService;

    protected final static String QUEUE_NAME = "maven_index_worker";
    protected Connection connection;

    public void execute() throws MojoExecutionException, MojoFailureException {
        try{
            super.execute();
            ApplicationContext context = new ClassPathXmlApplicationContext("applicationContext.xml");
            productService = (ProductService) context.getBean("productService");

            MavenRepository typesafeRepo = mavenRepositoryDao.findByName("typesafe");
            addRepo(typesafeRepo);

            initMavenRepository();

            doUpdateFromIndex();
        } catch( Exception exception ){
            logger.error(exception);
            throw new MojoExecutionException("Oh no! Something went wrong. Get in touch with the VersionEye guys and give them feedback.", exception);
        }
    }


    protected void doUpdateFromIndex() {
        try{
            initTheRabbit();

            String centralCache = getCacheDirectory(mavenRepository.getName());
            String centralIndex = getIndexDirectory(mavenRepository.getName());

            logger.info("Init mavenIndexer with " + centralCache + " and " + centralIndex);
            MavenIndexer mavenIndexer = new MavenIndexer();
            mavenIndexer.initCentralContext(mavenRepository.getUrl(), centralCache, centralIndex);
            mavenIndexer.updateIndex(mavenRepository.getUsername(), mavenRepository.getPassword());
            logger.info("Updating Index is finished");

            IndexingContext context = mavenIndexer.getCentralContext();
            IndexSearcher searcher  = context.acquireIndexSearcher();
            final IndexReader ir    = searcher.getIndexReader();
            for ( int i = 0; i < ir.maxDoc(); i++ ) {
                processArtifact(context, ir, i);
            }
            context.releaseIndexSearcher(searcher);

            mavenIndexer.closeIndexer();

            closeTheRabbit();
        } catch (Exception ex){
            logger.error("ERROR in doUpdateFromIndex" + ex.getMessage());
            logger.error("ERROR in doUpdateFromIndex", ex);
        }
    }


    protected void processArtifact(IndexingContext context, IndexReader indexReader, int i) {
        try {
            if (i % 100000 == 0){
                logger.info("curser i: " + i);
            }

            if ( indexReader.isDeleted( i ) ) {
                logger.info("-- Reader Deleted --");
                return ;
            }

            final Document doc = indexReader.document( i );
            final ArtifactInfo artifactInfo = IndexUtils.constructArtifactInfo(doc, context);
            if (artifactInfo == null || artifactInfo.groupId == null || artifactInfo.artifactId == null){
                logger.info("-- Artifact Info Incomplete --");
                return ;
            }

            createIfNotExist(artifactInfo, artifactInfo.sha1, "sha1");
            createIfNotExist(artifactInfo, artifactInfo.md5, "md5");

            if (skipKnown && productDao.doesVersionExistAlreadyByGA(artifactInfo.groupId.toLowerCase(), artifactInfo.artifactId.toLowerCase(), artifactInfo.version)){
                logger.info("-- Skipping " + artifactInfo.groupId + ":" + artifactInfo.artifactId + ":" + artifactInfo.version);
                return ;
            }

            processArtifact(artifactInfo);
        } catch (Exception ex) {
            logger.error("Error in processArtifact - " + ex.toString());
            logger.error("Error in processArtifact - ", ex);
        }
    }


    protected void processArtifact(ArtifactInfo artifactInfo) {
        String gav = artifactInfo.groupId + ":" + artifactInfo.artifactId + ":pom:" + artifactInfo.version;
        logger.info("-- Processing {}", gav);
        sendGav(gav, artifactInfo.lastModified);
    }


    protected void processArtifactAsync( ArtifactInfo artifactInfo ) {
        ExecutorService executor = Executors.newCachedThreadPool();
        Callable<Object> task = new MyCallable(artifactInfo);

        Future<Object> future = executor.submit(task);
        try {
            future.get(30, TimeUnit.MINUTES);
        } catch (TimeoutException ex) {
            logger.error("Timeout Exception for " + artifactInfo.groupId + ":" + artifactInfo.artifactId + ":" + artifactInfo.version + " - " + ex);
            logger.error(ex);
        } catch (InterruptedException ex) {
            logger.error("Interrupted Exception: " + artifactInfo.groupId + ":" + artifactInfo.artifactId + ":" + artifactInfo.version + " - " + ex);
            logger.error(ex);
        } catch (ExecutionException ex) {
            logger.error("Execution Exception: " + artifactInfo.groupId + ":" + artifactInfo.artifactId + ":" + artifactInfo.version + " - " + ex);
            logger.error(ex);
        } finally {
            future.cancel(true);
        }
    }


    public class MyCallable implements Callable<Object> {

        private ArtifactInfo artifactInfo;

        public MyCallable (ArtifactInfo artifactInf) {
            artifactInfo = artifactInf;
        }

        public Object call() throws Exception {
            logger.info("send " + artifactInfo.groupId + ":" + artifactInfo.artifactId + ":" + artifactInfo.version);
            String gav = artifactInfo.groupId + ":" + artifactInfo.artifactId + ":pom:" + artifactInfo.version;
            sendGav(gav, artifactInfo.lastModified);
            return null;
        }
    }


    protected void sendGav(String gav, long lastModified){
        try{
            String message = mavenRepository.getName() + "::" + mavenRepository.getUrl() + "::" + gav + "::" + lastModified;
            // Create a session.
            Session producerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

            // Create a queue named "MyQueue".
            Destination producerDestination = producerSession.createQueue(QUEUE_NAME);

            // Create a producer from the session to the queue.
            MessageProducer producer = producerSession.createProducer(producerDestination);
            producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

            // Create a message.
            TextMessage producerMessage = producerSession.createTextMessage(message);

            // Send the message.
            producer.send(producerMessage);

            logger.info(" [x] Sent '" + message + "'");
            producer.close();
            producerSession.close();
        } catch (Exception exception) {
            logger.error("urlToPom: " + gav + " - " + exception.toString());
            logger.error("Exception in sendGav - ", exception);
        }
    }


    protected void initTheRabbit(){
        try {
            String rabbitmqAddr = System.getenv("RM_PORT_5672_TCP_ADDR");
            String rabbitmqPort = System.getenv("RM_PORT_5672_TCP_PORT");
            if (rabbitmqAddr == null || rabbitmqAddr.isEmpty() || rabbitmqPort == null || rabbitmqPort.isEmpty()){
                Properties properties = getProperties();
                rabbitmqAddr = properties.getProperty("rabbitmq_addr");
                rabbitmqPort = properties.getProperty("rabbitmq_port");
            }
            connection = RabbitMqService.getConnection(rabbitmqAddr, new Integer(rabbitmqPort));
            logger.info("Connected to ActiveMQ");
        } catch (Exception exception){
            logger.error("ERROR in initTheRabbit - " + exception.toString());
            logger.error("ERROR in initTheRabbit - ", exception);
        }
    }


    protected void closeTheRabbit(){
        try{
            connection.close();
            String msg = "Connection to RabbitMQ closed.";
            logger.info(msg);
            System.out.println(msg);
        } catch (Exception exception){
            logger.error("ERROR in closeTheRabbit - ", exception.getMessage());
            logger.error(exception);
        }
    }


    private void initMavenRepository(){
        mavenRepository = mavenRepositoryDao.findByName("central");
        if (mavenRepository == null){
            mavenRepository = new MavenRepository();
            mavenRepository.setId("central");
            mavenRepository.setName("central");
            mavenRepository.setUrl("https://repo.maven.apache.org/maven2");
            mavenRepository.setLanguage("Java");
        }
    }


    protected void createIfNotExist( ArtifactInfo artifactInfo, String sha_value, String sha_method ){
        try{
            if (sha_value == null || sha_value.isEmpty()){
                return ;
            }
            Artefact artefact = artefactDao.getBySha(sha_value);
            if (artefact != null) {
                return ;
            }
            artefact = new Artefact();
            updateArtefact(artefact, artifactInfo);
            artefact.setSha_value(sha_value);
            artefact.setSha_method(sha_method);
            artefact.setUpdatedAt(new Date());
            artefactDao.create(artefact);
            logger.info("New SHA value " + sha_method + ": " + artifactInfo.sha1 );
        } catch (Exception ex) {
            logger.error("ERROR in createIfNotExist - ", ex);
        }
    }

    protected void updateArtefact(Artefact artefact, ArtifactInfo artifactInfo){
        artefact.setLanguage("Java");
        artefact.setProd_key(artifactInfo.groupId + "/" + artifactInfo.artifactId);
        artefact.setVersion(artifactInfo.version);
        artefact.setGroup_id(artifactInfo.groupId);
        artefact.setArtifact_id(artifactInfo.artifactId);
        artefact.setClassifier(artifactInfo.classifier);
        artefact.setPackaging(artifactInfo.packaging);
        artefact.setProd_type("Maven2");
    }


}
