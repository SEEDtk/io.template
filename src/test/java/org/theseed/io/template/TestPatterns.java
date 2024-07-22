/**
 *
 */
package org.theseed.io.template;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.regex.Matcher;

import org.junit.jupiter.api.Test;
import org.theseed.json.JsonConverter;

/**
 * @author Bruce Parrello
 *
 */
class TestPatterns {

    @Test
    void test() {
        Matcher m = JsonConverter.GENOME_KEY_PATTERN.matcher("genome_id");
        assertThat(m.matches(), equalTo(true));
        assertThat(m.group(1), nullValue());
        m = JsonConverter.GENOME_KEY_PATTERN.matcher("genome_id_2");
        assertThat(m.matches(), equalTo(true));
        assertThat(m.group(1), equalTo("_2"));
        m = JsonConverter.GENOME_KEY_PATTERN.matcher("genome_id_A");
        assertThat(m.matches(), equalTo(true));
        assertThat(m.group(1), equalTo("_A"));
        m = JsonConverter.GENOME_KEY_PATTERN.matcher("feature_id_A");
        assertThat(m.matches(), equalTo(false));
        m = JsonConverter.FEATURE_KEY_PATTERN.matcher("patric_id");
        assertThat(m.matches(), equalTo(true));
        assertThat(m.group(1), equalTo("patric"));
        assertThat(m.group(2), nullValue());
        m = JsonConverter.FEATURE_KEY_PATTERN.matcher("feature_id");
        assertThat(m.matches(), equalTo(true));
        assertThat(m.group(1), equalTo("feature"));
        assertThat(m.group(2), nullValue());
        m = JsonConverter.FEATURE_KEY_PATTERN.matcher("feature_id_a");
        assertThat(m.matches(), equalTo(true));
        assertThat(m.group(1), equalTo("feature"));
        assertThat(m.group(2), equalTo("_a"));
        m = JsonConverter.FEATURE_KEY_PATTERN.matcher("feature_id_b");
        assertThat(m.matches(), equalTo(true));
        assertThat(m.group(1), equalTo("feature"));
        assertThat(m.group(2), equalTo("_b"));
        m = JsonConverter.FEATURE_KEY_PATTERN.matcher("feat_id_b");
        assertThat(m.matches(), equalTo(false));
    }

}
