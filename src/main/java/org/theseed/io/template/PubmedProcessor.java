/**
 *
 */
package org.theseed.io.template;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.basic.BaseReportProcessor;
import org.theseed.basic.ParseFailureException;
import org.theseed.io.FieldInputStream;
import org.theseed.io.TabbedInputStream;

/**
 * This method extracts all the pubmed IDs in a JSON dump directory.  These can be used to get a full list of paper-related data from NCBI,
 * and then that list in turn can be used to import the paper descriptions into the template output.
 *
 * The positional parameters are the name of a control file and the name of the input master directory.  Normally, all the files in the
 * master directory will be examined.  If the "--recursive" option is specified, all the files in the subdirectories of the master
 * directory will be examined.
 *
 * The control file is tab-delimited, with headers.  The first column should contain the base name of a file, and the second column the
 * name of a field in that file which should contain PUBMED IDs.  Like any template input directory, the files can be JSON or tab-delimited,
 * according to the extension.
 *
 * The output report will be produced on the standard output.
 *
 * The command-line options are as follows:
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -o	output file for report (if not STDOUT)
 * -R	process all subdirectories of the master directory
 *
 * @author Bruce Parrello
 *
 */
public class PubmedProcessor extends BaseReportProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(PubmedProcessor.class);
    /** map of file base names to field-name lists */
    private Map<String, List<String>> fieldMap;
    /** list of input directory names */
    private File[] inDirs;
    /** file filter for subdirectories */
    private FileFilter DIR_FILTER = new FileFilter() {
        @Override
        public boolean accept(File pathname) {
            return pathname.isDirectory();
        }
    };

    // COMMAND-LINE OPTIONS

    /** TRUE if we are processing input subdirectories, not the input directory itself */
    @Option(name = "--recursive", aliases = { "-R" }, usage = "if specified, the subdirectories of the input directory will be processed")
    private boolean recursive;

    /** name of the input control file */
    @Argument(index = 0, metaVar = "controlFile.tbl", usage = "name of the input control file", required = true)
    private File controlFile;

    /** name of the input master directory */
    @Argument(index = 1, metaVar = "inDir", usage = "name of the input master directory", required = true)
    private File inDir;

    @Override
    protected void setReporterDefaults() {
        this.recursive = false;
    }

    @Override
    protected void validateReporterParms() throws IOException, ParseFailureException {
        // Verify the control file.
        if (! this.controlFile.canRead())
            throw new FileNotFoundException("Control file " + this.controlFile + " is not found or unreadable.");
        // Insure the input directory exists.
        if (! this.inDir.isDirectory())
            throw new FileNotFoundException("Input directory " + this.inDir + " is not found or invalid.");
        // Read in the control file and build the map.
        this.fieldMap = this.readFieldMap(this.controlFile);
        // Are we recursive?
        if (! this.recursive) {
            // No, just save the main directory in the directory list.
            this.inDirs = new File[] { this.inDir };
            log.info("Input directory {} will be scanned.", this.inDir);
        } else {
            // Yes. Get the subdirectories.
            this.inDirs = this.inDir.listFiles(DIR_FILTER);
            log.info("{} subdirectories of {} will be scanned.", this.inDirs.length, this.inDir);
        }
    }

    /**
     * Read the control file and return a map of file names to field lists.
     *
     * @param inFile	input control file
     *
     * @return a map of base filenames to lists of fields to examine for pubmed IDs
     *
     * @throws IOException
     */
    private Map<String, List<String>> readFieldMap(File inFile) throws IOException {
        Map<String, List<String>> retVal = new HashMap<String, List<String>>();
        try (TabbedInputStream inStream = new TabbedInputStream(inFile)) {
            log.info("Reading control data from {}.", inFile);
            int inCount = 0;
            for (var line : inStream) {
                // Field 0 is the base file name, field 1 the field name.
                String baseName = line.get(0);
                // We build the field lists as linked lists because we only rarely get more than one.
                List<String> fieldList = retVal.computeIfAbsent(baseName, x -> new LinkedList<String>());
                fieldList.add(line.get(1));
                inCount++;
            }
            log.info("{} field names read for {} input files per directory.", inCount, retVal.size());
        }
        return retVal;
    }

    @Override
    protected void runReporter(PrintWriter writer) throws Exception {
        // We accumulate the pubmed IDs in a set and then write the set at the end.
        // A tree set is used so that the output is sorted.
        Set<String> pmIDs = new TreeSet<String>();
        int fileCount = 0;
        // Loop through the input directories.
        for (File dir : this.inDirs) {
            // Process each possible input file in this directory.
            for (var fileEntry : this.fieldMap.entrySet()) {
                File inFile = new File(dir, fileEntry.getKey());
                fileCount++;
                if (inFile.canRead()) {
                    log.info("Reading IDs from {}.", inFile);
                    // Count the number of records and the number of IDs found.
                    int inCount = 0;
                    int idCount = 0;
                    try (FieldInputStream inStream = FieldInputStream.create(inFile)) {
                        // Find the fields of interest in this file.
                        List<String> fieldNames = fileEntry.getValue();
                        int[] idxes = new int[fieldNames.size()];
                        int i = 0;
                        for (String fieldName : fieldNames) {
                            idxes[i] = inStream.findField(fieldName);
                            i++;
                        }
                        // Now loop through the records.
                        for (var line : inStream) {
                            inCount++;
                            for (int idx : idxes) {
                                List<String> pubmeds = line.getList(idx);
                                pmIDs.addAll(pubmeds);
                                idCount += pubmeds.size();
                            }
                        }
                    }
                    if (inCount > 0)
                        log.info("{} records read from {}.  {} pubmed IDs found,", inCount, inFile, idCount);
                }
            }
        }
        // Now we have all the pubmed IDs.
        log.info("{} PUBMED IDs harvested from {} files.", pmIDs.size(), fileCount);
        // Write the PUBMED IDs to the output.
        writer.println("pubmed_id");
        for (String pmID : pmIDs)
            writer.println(pmID);
        writer.flush();
        log.info("PUBMED ID list written to output.");
    }


 }
