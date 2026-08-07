package com.power.posval.app.config;

import com.ctrm.ruleengine.api.RuleEngine;
import com.ctrm.ruleengine.core.DefaultRuleEngine;
import com.ctrm.ruleengine.core.cache.InMemoryRuleCache;
import com.ctrm.ruleengine.core.conflict.DefaultConflictResolver;
import com.ctrm.ruleengine.core.dao.json.JsonRuleDao;
import com.ctrm.ruleengine.core.dao.json.JsonRuleSetDao;
import com.ctrm.ruleengine.core.eval.DefaultDecisionEvaluator;
import com.ctrm.ruleengine.core.failure.DefaultFailureHandler;
import com.ctrm.ruleengine.core.resolve.CacheBackedRuleResolver;
import com.ctrm.ruleengine.model.Rule;
import com.ctrm.ruleengine.model.RuleSet;
import com.ctrm.ruleengine.mvel.MvelEvaluator;
import com.ctrm.ruleengine.spi.ExpressionEvaluator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.power.posval.app.pricing.PriceRuleDefinition;
import com.power.posval.app.pricing.RuleEngineBasedEvaluator;
import com.power.posval.domain.model.expression.PriceExpression;
import com.power.posval.domain.port.NumericPrecision;
import com.power.posval.domain.port.repository.PriceExpressionRepository;
import com.power.posval.domain.service.PriceEvaluator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Spring configuration for the MVEL rule-engine-based price evaluator.
 * Activated by pv.pricing.strategy=rule-engine.
 */
@Configuration
@ConditionalOnProperty(name = "pv.pricing.strategy", havingValue = "rule-engine")
public class RuleEngineConfig {

    @Bean
    public RuleEngine ruleEngine() throws IOException {
        // 1. Load rules from JSON
        Path rulesPath = extractToTempFile("stub/price-rules.json");
        JsonRuleDao ruleDao = new JsonRuleDao(rulesPath);

        // 2. Populate cache
        InMemoryRuleCache cache = new InMemoryRuleCache();
        for (Rule rule : ruleDao.findAll()) {
            cache.put(rule);
        }
        cache.markWarm();

        // 3. Load rulesets and index them
        Path rulesetsPath = extractToTempFile("stub/price-rulesets.json");
        JsonRuleSetDao ruleSetDao = new JsonRuleSetDao(rulesetsPath);
        CacheBackedRuleResolver resolver = new CacheBackedRuleResolver(cache);
        for (RuleSet rs : ruleSetDao.findAll()) {
            resolver.index(rs);
        }

        // 4. Build engine
        var decisionEvaluator = new DefaultDecisionEvaluator(
            new DefaultConflictResolver(),
            new DefaultFailureHandler());

        return new DefaultRuleEngine(
            resolver,
            decisionEvaluator,
            List.of(new MvelEvaluator()));
    }

    @Bean
    public List<PriceRuleDefinition> priceRuleDefinitions() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        var resource = new ClassPathResource("stub/price-rule-definitions.json");
        return mapper.readValue(
            resource.getInputStream(),
            new TypeReference<List<PriceRuleDefinition>>() {});
    }

    @Bean
    @Primary
    public PriceEvaluator ruleEngineBasedPriceEvaluator(
            RuleEngine ruleEngine,
            List<PriceRuleDefinition> priceRuleDefinitions,
            PriceExpressionRepository priceExpressionRepository,
            NumericPrecision np) {

        // Build reverse map: PriceExpression → exprId
        Map<PriceExpression, String> expressionToId = new HashMap<>();
        for (PriceRuleDefinition def : priceRuleDefinitions) {
            UUID id = UUID.fromString(def.priceExpressionId());
            priceExpressionRepository.findById(id).ifPresent(
                expr -> expressionToId.put(expr, def.priceExpressionId()));
        }

        // Build definition lookup: exprId → PriceRuleDefinition
        Map<String, PriceRuleDefinition> definitionsByExprId = new HashMap<>();
        for (PriceRuleDefinition def : priceRuleDefinitions) {
            definitionsByExprId.put(def.priceExpressionId(), def);
        }

        return new RuleEngineBasedEvaluator(
            ruleEngine, expressionToId, definitionsByExprId, np);
    }

    /**
     * Extract a classpath resource to a temp file (JsonRuleDao requires a Path).
     */
    private Path extractToTempFile(String classpathLocation) throws IOException {
        var resource = new ClassPathResource(classpathLocation);
        Path tempFile = Files.createTempFile("rule-engine-", ".json");
        tempFile.toFile().deleteOnExit();
        try (var in = resource.getInputStream();
             var out = Files.newOutputStream(tempFile)) {
            in.transferTo(out);
        }
        return tempFile;
    }
}
