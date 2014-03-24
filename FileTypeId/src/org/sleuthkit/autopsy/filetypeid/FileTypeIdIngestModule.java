/*
 * Autopsy Forensic Browser
 * 
 * Copyright 2013-2014 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.filetypeid;

import java.util.logging.Level;

import org.openide.util.NbBundle;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.ingest.FileIngestModule;
import org.sleuthkit.autopsy.ingest.IngestMessage;
import org.sleuthkit.autopsy.ingest.IngestServices;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.BlackboardArtifact;
import org.sleuthkit.datamodel.BlackboardAttribute;
import org.sleuthkit.datamodel.BlackboardAttribute.ATTRIBUTE_TYPE;
import org.sleuthkit.datamodel.TskData;
import org.sleuthkit.datamodel.TskData.FileKnown;
import org.sleuthkit.datamodel.TskException;
import org.sleuthkit.autopsy.ingest.IngestModule.ProcessResult;
import org.sleuthkit.autopsy.ingest.IngestModuleAdapter;

/**
 * Detects the type of a file based on signature (magic) values. Posts results
 * to the blackboard.
 */
public class FileTypeIdIngestModule extends IngestModuleAdapter implements FileIngestModule {

    private static final Logger logger = Logger.getLogger(FileTypeIdIngestModule.class.getName());
    private static final long MIN_FILE_SIZE = 512;
    private final FileTypeIdentifierModuleSettings settings;
    private long matchTime = 0;
    private int messageId = 0; // RJCTODO: If this is not made a thread safe static, duplicate message ids will be used 
    private long numFiles = 0;
    // The detector. Swap out with a different implementation of FileTypeDetectionInterface as needed.
    // If desired in the future to be more knowledgable about weird files or rare formats, we could 
    // actually have a list of detectors which are called in order until a match is found.
    private FileTypeDetectionInterface detector = new TikaFileTypeDetector();

    FileTypeIdIngestModule(FileTypeIdentifierModuleSettings settings) {
        this.settings = settings;
    }

    @Override
    public ProcessResult process(AbstractFile abstractFile) {
        // skip non-files
        if ((abstractFile.getType() == TskData.TSK_DB_FILES_TYPE_ENUM.UNALLOC_BLOCKS)
                || (abstractFile.getType() == TskData.TSK_DB_FILES_TYPE_ENUM.UNUSED_BLOCKS)) {

            return ProcessResult.OK;
        }

        if (settings.skipKnownFiles() && (abstractFile.getKnown() == FileKnown.KNOWN)) {
            return ProcessResult.OK;
        }

        if (abstractFile.getSize() < MIN_FILE_SIZE) {
            return ProcessResult.OK;
        }

        try {
            long startTime = System.currentTimeMillis();
            FileTypeDetectionInterface.FileIdInfo fileId = detector.attemptMatch(abstractFile);
            matchTime += (System.currentTimeMillis() - startTime);
            numFiles++;

            if (!fileId.type.isEmpty()) {
                // add artifact
                BlackboardArtifact bart = abstractFile.getGenInfoArtifact();
                BlackboardAttribute batt = new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_FILE_TYPE_SIG.getTypeID(), FileTypeIdentifierModuleFactory.getModuleName(), fileId.type);
                bart.addAttribute(batt);

                // we don't fire the event because we just updated TSK_GEN_INFO, which isn't displayed in the tree and is vague.
            }
            return ProcessResult.OK;
        } catch (TskException ex) {
            logger.log(Level.WARNING, "Error matching file signature", ex);
            return ProcessResult.ERROR;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error matching file signature", e);
            return ProcessResult.ERROR;
        }
    }

    @Override
    public void shutDown(boolean ingestJobCancelled) {
        StringBuilder detailsSb = new StringBuilder();
        detailsSb.append("<table border='0' cellpadding='4' width='280'>");
        detailsSb.append("<tr><td>").append(FileTypeIdentifierModuleFactory.getModuleName()).append("</td></tr>");
        detailsSb.append("<tr><td>")
                .append(NbBundle.getMessage(this.getClass(), "FileTypeIdIngestModule.complete.totalProcTime"))
                .append("</td><td>").append(matchTime).append("</td></tr>\n");
        detailsSb.append("<tr><td>")
                .append(NbBundle.getMessage(this.getClass(), "FileTypeIdIngestModule.complete.totalFiles"))
                .append("</td><td>").append(numFiles).append("</td></tr>\n");
        detailsSb.append("</table>");
        IngestServices.getDefault().postMessage(IngestMessage.createMessage(++messageId, IngestMessage.MessageType.INFO, FileTypeIdentifierModuleFactory.getModuleName(),
                NbBundle.getMessage(this.getClass(),
                "FileTypeIdIngestModule.complete.srvMsg.text"),
                detailsSb.toString()));
    }

    /**
     * Validate if a given mime type is in the detector's registry.
     *
     * @param mimeType Full string of mime type, e.g. "text/html"
     * @return true if detectable
     */
    public static boolean isMimeTypeDetectable(String mimeType) {
        FileTypeDetectionInterface detector = new TikaFileTypeDetector();
        return detector.isMimeTypeDetectable(mimeType);
    }
}