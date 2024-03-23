/**
 *
 */
package org.theseed.io.template;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.kohsuke.args4j.Argument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.basic.BaseProcessor;
import org.theseed.basic.ParseFailureException;
import org.theseed.json.FeatureJsonConverter;
import org.theseed.json.GenomeJsonConverter;
import org.theseed.json.JsonConverter;
import org.theseed.json.NormalJsonConverter;
import org.theseed.magic.FidMapper;


/**
 * This command processes JSON dumps for genomes and subsystems, adding magic word identifiers to the feature and
 * genome references.
 *
 * The first directory specified must contain the genome dumps.  The "genome.json" file will contain the genome ID and
 * name, and will be used to set the current-genome information.  Then the feature definitions will be pulled from
 * "genome_feature.json".  After that, the other files will be scanned for feature and genome IDs.  Whenever "genome_id"
 * occurs, "genome_word_id" will be added.  Whenever "patric_id" occurs, "feature_word_id" will be added.  If a "feature_id"
 * is present in a genome_feature record, it will be mapped to the generated feature word ID so that records which only have
 * feature IDs can also have the word IDs added.  In some cases, the ID field name will have a suffix "_a", "_b", "_1", "_2",
 * or so forth.  These are also handled.
 *
 * The positional parameters are the name of the primary genome dump master directory, and then the names of the other dump
 * directories. Each dump directory contains subdirectories with the json files.  All the json files are processed and then
 * copied back in place.  This is a slightly risky proposition.
 *
 * Currently, feature IDs are only updated in the master genome directory.  This is to prevent memory problems.  The hope is
 * that this is just a temporary measure, and a more permanent solution is to put the magic IDs in the actual database.
 *
 * The command-line options are as follows:
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 *
 * @author Bruce Parrello
 *
 */
public class MagicJsonProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(MagicJsonProcessor.class);
    /** feature ID mapper */
    private FidMapper fidMapper;
    /** list of genome directories to process */
    private File[] genomeDirs;
    /** this set contains the special file names for the genome processor */
    protected static final Set<String> SPECIAL_FILE_SET = Set.of("genome.json", "genome_feature.json");
    /** genome subdirectory filter */
    private static final FileFilter GENOME_DIR_FILTER = new FileFilter() {
        @Override
        public boolean accept(File pathname) {
            boolean retVal = pathname.isDirectory();
            if (retVal) {
                File gFile = new File(pathname, "genome.json");
                retVal = gFile.canRead();
            }
            return retVal;
        }
    };
    /** general subdirectory filter */
    private static final FileFilter SUB_DIR_FILTER = new FileFilter() {
        @Override
        public boolean accept(File pathname) {
            return pathname.isDirectory();
        }
    };
    /** JSON file filter */
    private static final FileFilter JSON_FILE_FILTER = new FileFilter() {
        @Override
        public boolean accept(File pathname) {
            boolean retVal = pathname.canRead();
            if (retVal)
                retVal = pathname.getName().endsWith(".json");
            return retVal;
        }
    };


    // COMMAND-LINE OPTIONS

    /** master genome dump directory */
    @Argument(index = 0, metaVar = "genomesDir", usage = "master genome JSON dump directory", required = true)
    private File genomesDir;

    /** additional directories */
    @Argument (index = 1, metaVar = "otherDir1 otherDir2 ...", usage = "other JSON dump directories to process")
    private List<File> otherDirs;

    @Override
    protected void setDefaults() {
        this.otherDirs = new ArrayList<File>();
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
        // Verify the genome directory.
        if (! this.genomesDir.isDirectory())
            throw new FileNotFoundException("Input genome master directory " + this.genomesDir + " is not found or invalid.");
        // Find all the genomes.
        this.genomeDirs = this.genomesDir.listFiles(GENOME_DIR_FILTER);
        if (this.genomeDirs.length == 0)
            throw new FileNotFoundException("No usable genome subdirectories found in " + this.genomesDir + ".");
        log.info("{} genome subdirectories found in {}.", this.genomeDirs.length, this.genomesDir);
        // Now verify the additional directories.
        for (File otherDir : otherDirs) {
            if (! otherDir.isDirectory())
                throw new FileNotFoundException("Additional master directory " + otherDir + " is not found or invalid.");
        }
        log.info("{} additional directories scheduled.", otherDirs.size());
        this.fidMapper = new FidMapper();
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // We process the genome master directory first.
        log.info("Processing {} genome subdirectories.", this.genomeDirs.length);
        int gCount = 0;
        for (File genomeDir : this.genomeDirs) {
            gCount++;
            log.info("Processing genome {} of {} in directory {}.", gCount, this.genomeDirs.length, genomeDir);
            // Get the list of json files.
            File[] jsonFiles = this.getJsonFiles(genomeDir);
            log.info("{} files to convert in {}.", jsonFiles.length, genomeDir);
            // Process the genome file first.
            GenomeJsonConverter gConverter = new GenomeJsonConverter(this.fidMapper, genomeDir);
            this.processConverter(gConverter);
            // Now process the feature file.
            FeatureJsonConverter fConverter = new FeatureJsonConverter(this.fidMapper, genomeDir);
            this.processConverter(fConverter);
            // Finally, process the other files.
            for (File jsonFile : jsonFiles) {
                String fileName = jsonFile.getName();
                if (! SPECIAL_FILE_SET.contains(fileName)) {
                    NormalJsonConverter converter = new NormalJsonConverter(this.fidMapper, genomeDir, fileName);
                    this.processConverter(converter);
                }
            }
        }
        // Now process the other directory groups.
        int otherDirCount = 0;
        for (File otherDir : this.otherDirs) {
            File[] subDirs = otherDir.listFiles(SUB_DIR_FILTER);
            log.info("{} subdirectories to process in {}.", subDirs.length, otherDir);
            for (File subDir : subDirs) {
                File[] jsonFiles = this.getJsonFiles(otherDir);
                if (jsonFiles.length == 0)
                    log.warn("No JSON files found in {}.", subDir);
                else {
                    log.info("{} files to convert found in {}.", jsonFiles.length, subDir);
                    for (File jsonFile : jsonFiles) {
                        String fileName = jsonFile.getName();
                        NormalJsonConverter converter = new NormalJsonConverter(this.fidMapper, subDir, fileName);
                        this.processConverter(converter);
                        otherDirCount++;
                    }
                }
            }
        }
        log.info("{} total additional directories processed.", otherDirCount);
    }


    /**
     * @return an array of the JSON files in a JSON dump directory
     *
     * @param dumpDir	dump directory to scan
     */
    private File[] getJsonFiles(File dumpDir) {
        return dumpDir.listFiles(JSON_FILE_FILTER);
    }

    /**
     * Process a JSON converter to produce a converted file.
     *
     * @param converter		converter to process
     *
     * @throws IOException
     */
    private void processConverter(JsonConverter converter) throws IOException {
        // Convert all the records.
        converter.process();
        // Save the file.
        converter.save();
    }

}
