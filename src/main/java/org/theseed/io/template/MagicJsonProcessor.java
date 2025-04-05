/**
 *
 */
package org.theseed.io.template;

import java.io.File;
import java.io.IOException;
import org.theseed.basic.ParseFailureException;
import org.theseed.json.GenomeJsonConverter;
import org.theseed.json.JsonConverter;
import org.theseed.json.JsonConverter.KeyMode;
import org.theseed.magic.FidMapper;
import org.theseed.magic.MagicFidMapper;

import com.github.cliftonlabs.json_simple.JsonException;


/**
 * This command processes JSON dumps for genomes and subsystems, adding magic word identifiers to the feature and
 * genome references.
 *
 * The first directory specified must contain the genome dumps.  The "genome.json" file will contain the genome ID and
 * name, and will be used to set the current-genome information.  Then the feature definitions will be pulled from
 * "genome_feature.json".  After that, the other files will be scanned for feature and genome IDs.  Whenever "genome_id"
 * occurs, "genome_word" will be added.  Whenever "patric_id" occurs, "feature_word" will be added.  If a "feature_id"
 * is present in a genome_feature record, it will be mapped to the generated feature word ID so that records which only have
 * feature IDs can also have the word IDs added.  In some cases, the ID field name will have a suffix "_a", "_b", "_1", "_2",
 * or so forth.  These are also handled.
 *
 * The positional parameters are the name of the primary genome dump master directory, and then the names of the other dump
 * directories. Each dump directory contains subdirectories with the json files.  All the json files are processed and then
 * copied into new directories.
 *
 * The command-line options are as follows:
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -D	output directory (default is "Updated" in the current directory)
 *
 * --clear	if specified, the output directory will be erased before processing
 *
 * @author Bruce Parrello
 *
 */
public class MagicJsonProcessor extends BaseJsonUpdateProcessor {

    /**
	 * Set the subclass-specific defaults and the key mode.
	 */
	@Override
	protected void setJsonUpdateDefaults() { }

	@Override
	protected KeyMode getKeyMode() {
		return JsonConverter.KeyMode.USE_WORD_KEY;
	}

	@Override
	protected FidMapper getFidMapper() {
		return new MagicFidMapper();
	}

	@Override
	protected void validateJsonUpdateParms() throws IOException, ParseFailureException { }

	@Override
	protected void convertGenome(FidMapper mapper, File genomeDir, File masterDir) throws IOException, JsonException {
        GenomeJsonConverter gConverter = new GenomeJsonConverter(mapper, genomeDir, JsonConverter.KeyMode.USE_WORD_KEY);
        File outFile = this.computeOutputFile(gConverter.getFile());
        this.processConverter(gConverter, outFile);
	}

	@Override
	protected void processConverter(JsonConverter converter, File targetFile) throws IOException {
		converter.process();
		converter.save(targetFile);
	}

	@Override
	protected File getOutputFile(File jsonFile) {
		return this.computeOutputFile(jsonFile);
	}

}
