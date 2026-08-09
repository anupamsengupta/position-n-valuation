---
description: "Quick D-1..D-14 constraint verification on the current codebase without a full review"
argument-hint: ""
allowed-tools: Bash, Grep, Read
---

Run the mechanical constraint checks from CLAUDE.md against the current codebase. Report pass/fail for each.

## D-13: Library modules are Spring-free
```bash
grep -rn "org.springframework" pv-domain pv-persistence pv-kafka pv-redis pv-guice
```
Pass = zero results.

## D-14: Simulator patterns confined to pv-app
```bash
grep -rn '"default"' pv-domain pv-persistence pv-kafka pv-redis pv-guice | grep -i tenant
```
Pass = zero results.

## D-13 supplement: No Spring DI annotations in library
```bash
grep -rn "@Component\|@Service\|@Repository\|@Configuration\|@Autowired\|@Value\|@ConditionalOnProperty" pv-domain pv-persistence pv-kafka pv-redis pv-guice
```
Pass = zero results.

## D-13 supplement: No Spring Data JPA anywhere
```bash
grep -rn "JpaRepository\|CrudRepository\|PagingAndSortingRepository\|@PersistenceContext" pv-domain pv-persistence pv-kafka pv-redis pv-guice pv-app
```
Pass = zero results.

## Guice-Spring drift: @Bean methods that call new on domain classes
```bash
grep -n "new " pv-app/src/main/java/com/power/posval/app/config/DomainServiceConfig.java 2>/dev/null | grep -v "import\|//"
```
Pass = zero results (all beans should delegate to injector.getInstance).

## Report
Present results as a table: Check | Result (PASS/FAIL) | Details.
If any check fails, list the offending files and line numbers.
