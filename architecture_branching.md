# Branching Architecture — How Git and Vitruv Fit Together

## The Core Assumption

The Git repository **wraps** the Vitruv project. Vitruv does not manage its own internal Git repo — it assumes the developer has already run `git init` and that the VSUM's storage folder sits inside (or is the same as) the Git repository root.

```
The developer creates a Git repo.
The developer creates a VSUM inside that repo.
Vitruv discovers the .git/ directory and integrates with it.
```

## What It Looks Like on Your File System

A typical project directory:

```
my-project/                          ← Git repo root AND VSUM storage root
│
├── .git/                            ← Standard Git internals (created by git init)
│   ├── objects/
│   ├── refs/
│   ├── HEAD
│   └── hooks/
│       ├── post-checkout            ← Installed by Vitruvius (GitHookInstaller)
│       ├── pre-commit               ← Blocks commit if VSUM is inconsistent
│       ├── post-commit              ← Generates semantic changelog
│       └── post-merge               ← Validates VSUM after merge
│
├── vsum/                            ← VSUM internal storage (created by VirtualModelBuilder)
│   ├── correspondences.correspondence   ← EMF XMI: which model elements correspond
│   ├── uuid.uuid                        ← Text file: UUID|HierarchicalId mappings
│   └── models.models                    ← Text file: URIs of all managed model resources
│
├── consistencymetadata/             ← Consistency rule metadata (created by VirtualModelBuilder)
│
├── .vitruvius/                      ← Vitruvius runtime metadata (created by hooks & watchers)
│   ├── branches/                    ← Branch lifecycle tracking
│   │   ├── main.metadata
│   │   └── feature-x.metadata
│   ├── changelogs/                  ← Human-readable commit changelogs
│   │   └── abc1234.txt
│   ├── semantic-changelogs/         ← EChange-level changelogs (JSON, for merge)
│   │   └── abc1234.changelog
│   ├── merges/                      ← Merge audit trail
│   │   └── def5678.metadata
│   ├── reload-trigger               ← Ephemeral: post-checkout → watcher IPC
│   ├── validate-trigger             ← Ephemeral: pre-commit → watcher IPC
│   ├── post-commit-trigger          ← Ephemeral: post-commit → watcher IPC
│   └── merge-trigger               ← Ephemeral: post-merge → watcher IPC
│
├── example.model                    ← Your EMF model files (XMI)
├── example.model2                   ← Second model (kept consistent by reactions)
└── pom.xml                          ← Build file
```

### What Git Tracks vs. What Is Ephemeral

| Path | Git-tracked? | Purpose |
|------|:---:|---------|
| `example.model`, `example.model2` | Yes | Your actual model files |
| `vsum/correspondences.correspondence` | Yes | Element correspondences |
| `vsum/uuid.uuid` | Yes | UUID mappings (element identity across branches) |
| `vsum/models.models` | Yes | Registry of managed models |
| `.vitruvius/branches/*.metadata` | Yes | Branch lifecycle history |
| `.vitruvius/changelogs/*.txt` | Yes | Commit audit trail |
| `.vitruvius/semantic-changelogs/*.changelog` | Yes | EChange logs for merge |
| `.vitruvius/reload-trigger` | **No** | Ephemeral IPC (trigger files) |
| `.vitruvius/validate-trigger` | **No** | Ephemeral IPC |
| `.vitruvius/.reload.lock` | **No** | OS-level file lock |
| `consistencymetadata/` | Depends | Rule metadata |

The `GitHookInstaller.installGitignore()` creates a `.gitignore` that excludes the ephemeral trigger and lock files.

## How JGit Is Used

Vitruv uses **JGit** (the pure-Java Git implementation) for all Git operations. It never shells out to the `git` CLI from Java code.

### Opening the Repository

```java
// BranchManager constructor:
public BranchManager(Path repoRoot) {
    if (!Files.isDirectory(repoRoot.resolve(".git"))) {
        throw new IllegalArgumentException("Not a Git repository: " + repoRoot);
    }
    this.repoRoot = repoRoot;
}

// Every operation opens Git fresh (try-with-resources):
try (Git git = Git.open(repoRoot.toFile())) {
    git.branchCreate().setName("feature-x").setStartPoint(commitId).call();
}
```

JGit requires `.git/` to exist. Vitruv validates this in the `BranchManager` constructor.

### Key JGit Operations Used

| Operation | JGit API | Where Used |
|-----------|----------|------------|
| Open repo | `Git.open(dir)` | BranchManager, PostCommitHandler, GitStateLoader |
| Create branch | `git.branchCreate().setName(n).setStartPoint(ref).call()` | BranchManager.createBranch() |
| Switch branch | `git.checkout().setName(n).call()` | BranchManager.switchBranch() |
| Delete branch | `git.branchDelete().setBranchNames(n).setForce(true).call()` | BranchManager.deleteBranch() |
| List branches | `git.branchList().call()` | BranchManager.listBranches() |
| Resolve ref | `repo.findRef("refs/heads/" + name)` | BranchManager.resolveBranchIdentifier() |
| Read commit | `RevWalk.parseCommit(objectId)` | PostCommitHandler, GitStateLoader |
| Find merge base | `RevWalk` with `MERGE_BASE` filter | GitStateLoader.findMergeBase() |
| Walk commit range | `RevWalk.markStart() / markUninteresting()` | ChangeExtractor.getCommitsBetween() |
| Clone repo | `Git.cloneRepository().setURI(uri).call()` | GitStateLoader.checkoutStateAtCommit() |
| Stage file | `git.add().addFilepattern(path).call()` | VsumPostCommitWatcher (auto-stage changelogs) |

### What About Native Git?

The **Git hooks** (`.git/hooks/post-checkout`, etc.) are Bash scripts that use the native `git` CLI — not JGit. This is because Git invokes hooks as shell scripts, not Java programs.

The hooks use native `git` for:
- `git rev-parse --show-toplevel` (find repo root)
- `git rev-parse HEAD` (get current commit SHA)
- `git symbolic-ref --short HEAD` (get current branch name)
- `git reflog` (determine parent branch)
- `git diff-tree` (check if commit has model files)

The hooks **don't modify** the repo — they only read state and write trigger files.

## The Two Communication Paths

### Path 1: Programmatic API (Java ↔ Java)

```
Your Code                BranchManager              VirtualModel
   │                         │                          │
   ├─ switchBranch("f") ───→ │                          │
   │                         ├─ git.checkout("f") ────→ │ (files change on disk)
   │                         ├─ handler.onBranchSwitch()│
   │                         │                          ├─ reload()
   │                         │                          │  ├─ unload all resources
   │                         │                          │  ├─ clear UUID resolver
   │                         │                          │  └─ reload from disk
   │                         │                          │
   │  ← view shows new branch state ─────────────────── │
```

Direct, synchronous. No trigger files needed.

### Path 2: CLI (Bash hook → trigger file → Java watcher)

```
Developer          Git Hook (Bash)       .vitruvius/        Java Watcher         VirtualModel
    │                   │                    │                   │                    │
    ├─ git checkout ──→ │                    │                   │                    │
    │                   ├─ write ──────────→ reload-trigger      │                    │
    │                   │                    │                   │                    │
    │                   │                    │   ← poll (500ms)─ │                    │
    │                   │                    │                   ├─ read & delete     │
    │                   │                    │                   ├─ onBranchSwitch() ─│
    │                   │                    │                   │                    ├─ reload()
    │                   │                    │                   │                    │
```

Asynchronous. The hook writes a trigger file; a background Java thread polls for it. This allows native `git` commands to work with Vitruv without the developer calling any Java API.

## How the VSUM Relates to the Git Repo

The VSUM is **not aware of Git**. It just reads and writes files to its storage folder. The branching system is layered on top:

```
┌─────────────────────────────────────────────┐
│  Git Repository (.git/)                     │
│  ┌───────────────────────────────────────┐  │
│  │  VSUM Storage (vsum/)                 │  │
│  │  - reads/writes model XMI files       │  │
│  │  - reads/writes uuid.uuid            │  │
│  │  - reads/writes correspondences       │  │
│  │  - reload() discards & reloads all    │  │
│  └───────────────────────────────────────┘  │
│  ┌───────────────────────────────────────┐  │
│  │  Branching Layer (.vitruvius/)        │  │
│  │  - BranchManager (JGit operations)    │  │
│  │  - Git hooks (trigger files)          │  │
│  │  - Watchers (poll & react)            │  │
│  │  - PostCheckoutHandler → reload()     │  │
│  └───────────────────────────────────────┘  │
│  ┌───────────────────────────────────────┐  │
│  │  Merge Layer (branch/merge/)          │  │
│  │  - ChangeLogCapture (intercept)       │  │
│  │  - SemanticChangeLog (persist)        │  │
│  │  - SemanticMergeEngine (replay)       │  │
│  └───────────────────────────────────────┘  │
│                                             │
│  example.model   example.model2   pom.xml   │
└─────────────────────────────────────────────┘
```

The key insight: **`VirtualModel.reload()`** is the single integration point. When Git changes the files on disk (via checkout, merge, etc.), the branching layer calls `reload()` and the VSUM refreshes its in-memory state from the new files. The VSUM doesn't know or care that Git caused the files to change.

## Setting It Up in Your Own Project

```java
// 1. You must have a Git repo
Path projectDir = Path.of("/my/project");
Git.init().setDirectory(projectDir.toFile()).setInitialBranch("main").call();

// 2. Create a VSUM in the same directory
InternalVirtualModel vsum = new VirtualModelBuilder()
    .withStorageFolder(projectDir)
    .withUserInteractorForResultProvider(interactionProvider)
    .withChangePropagationSpecifications(myReactionsSpec)
    .buildAndInitialize();

// 3. Install Git hooks
GitHookInstaller installer = new GitHookInstaller(projectDir);
installer.installAllHooks();

// 4. Start background watchers
VsumReloadWatcher reloadWatcher = new VsumReloadWatcher(vsum, projectDir);
reloadWatcher.start();

VsumValidationWatcher validationWatcher = new VsumValidationWatcher(vsum, projectDir);
validationWatcher.start();

// 5. Set up branch manager
BranchManager branchManager = new BranchManager(projectDir);
branchManager.setPostCheckoutHandler(new PostCheckoutHandler(vsum));

// 6. (Optional) Set up semantic change log capture
ChangeLogCapture capture = ChangeLogCapture.create(
    vsum.getUuidResolver(),
    vsum.getViewSourceModels().iterator().next().getResourceSet());
vsum.addChangePropagationListener(capture);

VsumPostCommitWatcher postCommitWatcher = new VsumPostCommitWatcher(projectDir, capture);
postCommitWatcher.start();

// Now: use VSUM normally, commit with git, switch branches — everything stays consistent
```

## Concurrency and Locking

- **Reload**: OS-level `FileLock` on `.vitruvius/.reload.lock` prevents concurrent reloads
- **Validation**: OS-level `FileLock` on `.vitruvius/.validation.lock`; request IDs (UUIDs) allow concurrent commits
- **Watchers**: Single daemon threads polling at 500ms intervals
- **Trigger files**: Written atomically by hooks, consumed (read + delete) by watchers
