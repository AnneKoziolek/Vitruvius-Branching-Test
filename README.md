# Vitruvius Branching Test

Test and demonstration project for the Vitruvius branching and semantic merge features.
Uses two bidirectionally synchronized EMF models (`model` and `model2`) with a single
Reaction set (`Model2Model2ChangePropagationSpecification`).

## Prerequisites

- Java 17+
- The upstream Vitruvius dependencies must be built first:

```bash
cd /workspace/Vitruv-Change   && ./mvnw clean install -Dmaven.test.skip=true
cd /workspace/Vitruv          && ./mvnw clean install -Dmaven.test.skip=true
cd /workspace/Vitruv-Server   && ./mvnw clean install -Dmaven.test.skip=true
```

## Build

```bash
cd /workspace/Vitruvius-Branching-Test
./mvnw clean install -Dmaven.test.skip=true
```

## Project Structure

| Module | Description |
|--------|-------------|
| `model/` | Two EMF metamodels: `model.ecore` (System/Component/Link) and `model2.ecore` (Root/Entity/Link) |
| `viewtype/` | `ChangeTransformingViewType` for filtered views with change transformation |
| `consistency/` | Reactions DSL rules (`templateReactions.reactions`) for bidirectional model-model2 sync |
| `vsum/` | VSUM setup, integration tests, and manual test harnesses |

## Running Tests

```bash
# All automated tests:
./mvnw -pl vsum verify

# Semantic merge tests:
./mvnw -pl vsum test -Dtest=SemanticMergeIntegrationTest

# Branch switching integration:
./mvnw -pl vsum test -Dtest=BranchSwitchingIntegrationTest

# Git hook integration:
./mvnw -pl vsum test -Dtest=GitCheckoutIntegrationTest

# Pre-commit validation:
./mvnw -pl vsum test -Dtest=PreCommitValidationIntegrationTest
```

## Git Merge Driver

The semantic merge can be used through Git's custom merge driver mechanism.

### Setup

```bash
# 1. Register the merge driver
git config merge.vitruvius.name "Vitruvius semantic merge"
git config merge.vitruvius.driver "/path/to/vitruvius-merge.sh %O %A %B %L %P"

# 2. Associate model files
echo '*.model merge=vitruvius' >> .gitattributes

# 3. Create configuration
mkdir -p .vitruvius
cat > .vitruvius/merge-driver.properties <<EOF
specifications=mir.reactions.model2Model2.Model2Model2ChangePropagationSpecification
EOF
```

The wrapper script `vitruvius-merge.sh` (see `BrakeCaseStudy/scripts/`) builds the classpath
from Maven and invokes `GitMergeDriver`. Adapt the classpath construction if using this project
instead of BrakeCaseStudy.

### Limitations

- **Prototype.** The Git merge driver integration works programmatically (JUnit tests) but
  has not been tested end-to-end via `git merge` on the command line in production settings.
- **No interactive conflict resolution.** Blocking conflicts abort the merge; there is no
  UI for presenting or resolving semantic conflicts.
- **Cold start.** First invocation loads the JVM and EMF runtime, taking several seconds.
- **Fixed Reactions.** Both branches must use the same Reaction set.
- **History.** `git blame` shows only the merge commit; richer provenance is in the semantic
  changelogs (`.vitruvius/semantic-changelogs/`) but not surfaced by Git.

## Manual Interactive Testing

For real-time, two-terminal testing of branching features:

```bash
./mvnw exec:java -pl vsum \
    -Dexec.mainClass="tools.vitruv.methodologisttemplate.vsum.ManualTest" \
    -Dexec.args="$HOME/vitruvius-manual-test" \
    -Dexec.classpathScope=test
```

See `MANUAL_TEST_SCRIPT.md` for guided scenarios.
