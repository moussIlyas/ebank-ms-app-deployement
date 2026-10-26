# ebank-app — Deployment Architecture, Problems & Solution

## 1. Existing architecture

### 1.1 Big picture

```
 push to GitLab
      │
      ▼
┌──────────────────────────────┐        SSH (key from CI var)        ┌──────────────────────────────────────────┐
│ GitLab CI (.gitlab-ci.yml)   │ ───────────────────────────────────▶ │  VM  51.170.134.36 (user: ubuntu)        │
│ image: alpine/ansible        │                                     │                                          │
│ stage: run                   │                                     │  1) role "docker"      (install Docker)  │
│  - write $SSH_PRIVATE_KEY    │                                     │  2) role "application" (clone + compose)│
│  - ansible-playbook          │                                     │                                          │
│    --syntax-check + run      │                                     │  ┌────────────────────────────────────┐  │
└──────────────────────────────┘                                     │  │ docker compose up (base file only) │  │
                                                                     │  │  mariadb + 5 Spring services       │  │
                                                                     │  └────────────────────────────────────┘  │
                                                                     └──────────────────────────────────────────┘
```

### 1.2 Components

| File | Role |
|---|---|
| `.gitlab-ci.yml` | Single job `run`: prepares SSH key from `$SSH_PRIVATE_KEY`, syntax-checks then executes the playbook |
| `ansible/inventory.yaml` | One host `server1` = `51.170.134.36`, user `ubuntu`, key `~/.ssh/ssh-key-2026-10-16.key` |
| `ansible/playbook.yaml` | Play `Configure application servers`, `become: true`, roles `[docker, application]` |
| `ansible/roles/docker/tasks/main.yml` | Installs Docker CE + compose plugin from Docker's official apt repo, enables the service (idempotent) |
| `ansible/roles/application/tasks/main.yml` | Clones the app repo to `/home/ubuntu/ebank-ms-app`, runs `community.docker.docker_compose_v2` (`project_src: ebank-ms-app`), prints the output |
| `ebank-ms-app/docker-compose.yml` | Base stack: MariaDB + 5 services (discovery, gateway, customer, ebank, bot), builds each image from the shared `Dockerfile` with `--build-arg MODULE=…`. **No host ports published.** `config-repo/` bind-mounted read-only; `.env` injected |
| `ebank-ms-app/docker-compose.prod.yml` | Prod overlay: `SPRING_PROFILES_ACTIVE=pro`, publishes **gateway 9999**, memory limits, read-only rootfs, cap_drop — **never used by the pipeline** |
| `ebank-ms-app/Dockerfile` | Multi-stage Maven→JRE build, one image per module, `.m2` BuildKit cache, jar copied into `/app/<module>` |
| `ebank-ms-app/config-repo/*.properties` | Runtime configuration, mounted into containers (Spring loads `../config-repo/…` at **startup only**) |

### 1.3 Execution flow per push

1. GitLab runner starts `alpine/ansible`, writes the private key, enters `ansible/`.
2. `ansible-playbook --syntax-check` — YAML/static validation only (**no Java tests**).
3. Playbook runs over SSH:
   - **docker role**: apt install Docker (already present → reports `ok`, no change).
   - **application role**: `git` module pulls latest code to `/home/ubuntu/ebank-ms-app`,
     then `docker compose up` (default build policy = *build only if the image is missing*).

---

## 2. Problems

### P1 — Nothing is tested before deploy (correctness)

- Only stage is `run` = Ansible syntax check. `mvn test` never executes; the 6 existing
  `contextLoads()` tests are dead code in CI.
- A broken build or failing test deploys just as happily as a good one.

### P2 — Code changes never reach the running containers (correctness)

- The `git` task **does** update the sources on the VM, but `docker compose up` without
  `build: always` only builds an image **when it is missing**. Images already exist →
  **no rebuild, no recreate** → containers keep serving the **old jar**.
- Result: adding an entity / changing Java code has **zero effect** in production.

### P3 — Config changes never reach the running containers (correctness)

- `config-repo/` is pulled onto the VM and is a bind mount, so the file on disk changes —
  but Compose does not restart containers when mounted file *contents* change, and Spring
  reads `.properties` **once at startup**.
- `.env` is gitignored → never updated by the pull; env vars are baked in at container
  creation anyway.
- `POST /actuator/refresh` could reload property files live, but it is **not exposed**
  (`management.endpoints.web.exposure.include=health,info` in `config-repo/global.properties`)
  and nothing in the pipeline would call it.

### P4 — Rebuilding the whole stack is overkill (performance)

If P2 is fixed naively (`build: always` for everything), every push compiles **all 5 Maven
modules inside Docker on the VM**:

- ~5 full `mvn package` runs per deploy (minutes each on a small VM, plus RAM pressure).
- All 5 containers recreated → whole platform restarts even when only one service changed.
- No change detection exists: a README edit triggers the same work as a database schema change.
- Build quality varies with VM size; the CI itself gives no feedback (builds happen at deploy time,
  not in CI).

### P5 — The prod overlay is never applied (correctness)

- Ansible runs only `docker-compose.yml`. The base file publishes **no ports**, so the gateway
  is unreachable from outside the VM; `SPRING_PROFILES_ACTIVE=pro`, memory limits, read-only
  rootfs and `cap_drop` are all skipped.

### P6 — Fragile / implicit assumptions (ops)

| Issue | Where |
|---|---|
| `project_src: ebank-ms-app` is a **relative** path — works only because it resolves against the SSH home dir | `application/tasks/main.yml:9` |
| `ansible.builtin.git` needs the **`git` binary on the VM** — the docker role never installs it (minimal cloud images often lack it) | `roles/docker/tasks/main.yml` |
| `.env` must pre-exist on the VM (compose fails with `:?missing`), but nothing provisions it | `docker-compose.yml:74-76` |
| Repo mismatch: this repo pushes to **GitLab** (`ilyas1280543/ebank-app`), playbook clones **GitHub** (`moussIlyas/ebank-ms-app`) | `application/tasks/main.yml:4` |
| No rollback: images are untagged/untied to commits; `git` always tracks branch HEAD | — |
| `ANSIBLE_HOST_KEY_CHECKING=false` — blind SSH, MITM-able (common shortcut, but real) | `.gitlab-ci.yml:8` |
| No concurrency control: two pipeline runs can race each other on the same VM | — |

---

## 3. Solution

Principle (standard monorepo CI/CD practice): **detect → build → deploy are three separate
layers.** Always run the *full* environment, but rebuild/recreate **only what changed**;
unchanged services keep their running containers untouched.

### Phase 1 — Make changes actually take effect (correctness)

`ansible/roles/application/tasks/main.yml`:

```yaml
---
- name: Clone git repo
  ansible.builtin.git:
    repo: https://gitlab.com/ilyas1280543/ebank-app.git   # the repo that is actually pushed
    dest: /home/ubuntu/ebank-ms-app
    version: main

- name: Deploy app (base + prod overlay, rebuild changed, wait healthy)
  community.docker.docker_compose_v2:
    project_src: /home/ubuntu/ebank-ms-app          # absolute, not relative
    files:                                          # apply the prod overlay too
      - docker-compose.yml
      - docker-compose.prod.yml
    build: always                                   # rebuild images (cache makes unchanged modules cheap)
    wait: true                                      # block until healthchecks pass
  register: output
```

Also:

- add `git` to the apt package list in `roles/docker/tasks/main.yml` (or a `common` role);
- provision `.env` once (manually or via Ansible `template`/`copy` from a CI variable — never commit it);
- fix the repo URL (P6) or set up a GitLab→GitHub mirror.

**Effect:** code changes are rebuilt & replaced; prod overlay (port 9999, `pro` profile,
limits) is active.

### Phase 2 — Stop rebuilding the world (performance)

#### 2a. Change detection job (GitLab)

```yaml
detect:
  stage: .pre
  image: alpine/git
  script:
    - |
      BEFORE="${CI_COMMIT_BEFORE_SHA:-}"
      if [ -z "$BEFORE" ] || ! git cat-file -e "$BEFORE" 2>/dev/null; then
        BEFORE="$(git rev-parse HEAD~1 2>/dev/null || git rev-list --max-parents=0 HEAD)"
      fi
      CHANGED=$(git diff --name-only "$BEFORE" "$CI_COMMIT_SHA")
      SERVICES=""
      for m in discovery-service gateway-service customer-service ebank-service ebank-bot; do
        echo "$CHANGED" | grep -q "^ebank-ms-app/$m/" && SERVICES="$SERVICES,$m"
      done
      # shared files => everything must rebuild
      echo "$CHANGED" | grep -qE '^ebank-ms-app/(pom\.xml|Dockerfile|docker-compose)' \
        && SERVICES=",discovery-service,gateway-service,customer-service,ebank-service,ebank-bot"
      # config-only change => no build at all
      if [ -z "$SERVICES" ] && echo "$CHANGED" | grep -q '^ebank-ms-app/config-repo/'; then
        MODE=restart
      else
        MODE=deploy
      fi
      echo "CHANGED_SERVICES=${SERVICES#,}" >> deploy.env
      echo "DEPLOY_MODE=$MODE" >> deploy.env
  artifacts:
    reports:
      env: deploy.env
```

(Alternative: GitLab-native `rules: changes:` per job — note it always evaluates `true`
on the first push of a new branch/tag.)

#### 2b. Deploy only the affected subset

```yaml
- name: Rebuild + recreate only changed services
  community.docker.docker_compose_v2:
    project_src: /home/ubuntu/ebank-ms-app
    files: [docker-compose.yml, docker-compose.prod.yml]
    services: "{{ CHANGED_SERVICES.split(',') | select | list }}"   # e.g. ["customer-service"]
    build: always
    wait: true
  when: DEPLOY_MODE | default('deploy') == 'deploy' and CHANGED_SERVICES | length > 0

- name: Config-only change -> restart, zero builds
  community.docker.docker_compose_v2:
    project_src: /home/ubuntu/ebank-ms-app
    files: [docker-compose.yml, docker-compose.prod.yml]
    state: restarted
    wait: true
  when: DEPLOY_MODE | default('deploy') == 'restart'
```

`services:` is a supported parameter of `docker_compose_v2` — Compose builds/creates
**only the listed services**; every other container keeps running as-is (the environment
stays complete, only the changed part is replaced).

#### Result matrix

| Change you push | Build | Containers touched | Time cost |
|---|---|---|---|
| `customer-service/**` (Java) | 1 module image | only `ebank-customer` recreated | ~1 Maven module |
| root `pom.xml` / `Dockerfile` / compose file | all 5 | all 5 recreated | full (justified) |
| `config-repo/**` only | **none** | restart only | seconds |
| `README.md` / `ansible/**` | none (or Ansible-only run) | none | seconds |
| First deploy / new VM | all 5 | all 5 | one-time full build |

### Phase 3 — Optional upgrades (industry-standard next steps)

1. **Add a `test` stage before deploy** (`mvn -B verify` in a Maven image) — deploy only on green.
2. **Build once, deploy many:** build images **in CI** (or on the VM only on cache miss), tag
   `:$CI_COMMIT_SHA`, push to a registry (GitLab Container Registry); the VM just
   `pull` + `up`. The VM never compiles Maven again → fastest, rollback = redeploy an old tag.
3. **Live config reload:** expose `refresh` in `management.endpoints.web.exposure.include`
   and call `POST /actuator/refresh` for `@RefreshScope` values — avoids even the restart
   for config-only tweaks (env-var-based settings still need a recreate).
4. **Safety:** enable SSH host-key checking against a pinned `known_hosts`, serialize
   pipelines (`resource_group: deploy`), and tag images for rollback.

---

## 4. Summary

| # | Problem | One-line fix |
|---|---|---|
| P1 | No tests in CI | add `test` stage (`mvn verify`) before `run` |
| P2 | Code changes never deployed (no rebuild) | `build: always` + recreate on compose task |
| P3 | Config changes never applied (no restart) | `state: restarted` / optional `/actuator/refresh` |
| P4 | Full-stack rebuild is overkill | detect changed paths → `services:` subset; config-only = restart |
| P5 | Prod overlay unused (no ports, no hardening) | `files: [docker-compose.yml, docker-compose.prod.yml]` |
| P6 | Relative path, missing `git`, `.env`, repo mismatch, no rollback | absolute `project_src`, install `git`, provision `.env`, fix repo URL, tag images |
