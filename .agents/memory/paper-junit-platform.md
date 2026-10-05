---
name: JUnit 5 on Paper
description: Gradle test configuration needed for Paper plugin modules using JUnit Jupiter.
---

Paper modules using JUnit 5 must explicitly enable Gradle's JUnit Platform and include a Jupiter engine at test runtime.

**Why:** The default Gradle test platform did not discover the JUnit 5 suite, so the test task failed or ran no tests until the platform and engine were configured.

**How to apply:** When adding or changing Paper-module tests, check the module's test dependencies and `Test` task configuration, then run the module's Gradle test task and confirm tests were actually executed.