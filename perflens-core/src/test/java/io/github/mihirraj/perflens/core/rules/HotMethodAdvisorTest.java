package io.github.mihirraj.perflens.core.rules;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HotMethodAdvisorTest {

    @Test
    void regexCompileInLoopIsRecognised() {
        Map<String, Long> callees = Map.of("java.lang.String.replaceAll", 70L, "(own code)", 30L);
        Map<String, Long> leaves = Map.of("java.util.regex.Pattern.compile", 60L, "java.util.regex.Matcher.find", 10L);
        List<String> advice = HotMethodAdvisor.advise(callees, leaves, 100, null);
        assertThat(advice.get(0)).contains("static final Pattern");
    }

    @Test
    void jacksonMapperCreationBeatsGenericJackson() {
        Map<String, Long> callees = Map.of("com.fasterxml.jackson.databind.ObjectMapper.<init>", 80L);
        Map<String, Long> leaves = Map.of("com.fasterxml.jackson.databind.ser.BeanSerializerFactory.constructBeanOrAddOnSerializer", 80L);
        List<String> advice = HotMethodAdvisor.advise(callees, leaves, 100, null);
        assertThat(advice).anyMatch(a -> a.contains("new ObjectMapper is created"));
    }

    @Test
    void specificAdviceWinsWithinFamilyEvenWhenGenericHasMoreSamples() {
        // generic regex matching has more samples, but compiling per call is the actionable root cause
        Map<String, Long> callees = Map.of("java.lang.String.replaceAll", 40L);
        Map<String, Long> leaves = Map.of("java.util.regex.Matcher.find", 70L, "java.util.regex.Pattern.compile", 15L);
        List<String> advice = HotMethodAdvisor.advise(callees, leaves, 100, null);
        assertThat(advice).filteredOn(a -> a.toLowerCase().contains("regex")).hasSize(1);
        assertThat(advice.get(0)).contains("static final Pattern").contains("(70%");
    }

    @Test
    void ownCodeFallback() {
        List<String> advice = HotMethodAdvisor.advise(Map.of("(own code)", 90L), Map.of("com.acme.Foo.bar", 90L), 100, null);
        assertThat(advice).singleElement().satisfies(a -> assertThat(a).startsWith("Most samples are in this method's own code"));
    }
}
