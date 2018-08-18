package com.versioneye.mojo;

import com.versioneye.domain.Artefact;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.context.IndexUtils;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

@Mojo( name = "centralSha", defaultPhase = LifecyclePhase.PROCESS_SOURCES )
public class CentralMojoSha extends CentralMojo {

    protected void processArtifact(IndexingContext context, IndexReader indexReader, int i) {
        try {
            if (i % 100000 == 0){
                logger.info("curser i: " + i);
            }

            if ( indexReader.isDeleted( i ) )
                return ;

            final Document doc = indexReader.document( i );
            final ArtifactInfo artifactInfo = IndexUtils.constructArtifactInfo(doc, context);
            if (artifactInfo == null || artifactInfo.groupId == null || artifactInfo.artifactId == null){
                return ;
            }

            createIfNotExist(artifactInfo, artifactInfo.sha1, "sha1");
            createIfNotExist(artifactInfo, artifactInfo.md5, "md5");
        } catch (Exception ex) {
            logger.error("Error in processArtifact - " + ex.toString());
            logger.error(ex);
        }
    }

}
