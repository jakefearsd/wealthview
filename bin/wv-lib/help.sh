# shellcheck shell=bash
# help.sh — top-level help and per-subcommand --help dispatch.

wv_help() {
    cat <<'EOH'
wv(1) — WealthView admin command surface
========================================

SYNOPSIS
    wv [--config FILE] [--host USER@HOST] <subcommand> [options]
    wv <subcommand> --help            # per-subcommand details
    wv help                           # this page

DESCRIPTION
    wv is a single entry point for every routine WealthView operation:
    bring the stack up or down, take and verify backups, perform an
    in-place upgrade with auto-rollback, restore from a backup, migrate
    the deployment to another host, rotate secrets, validate config.

    It is designed to run in two modes:

      - Development: invoked from the source tree (./wv up etc.). No
        configuration needed; it reads docker-compose.yml and .env from
        the repo.

      - Production: installed as /usr/local/bin/wv on a server that has
        only the containers (no source tree). All paths come from a
        config file at /etc/wealthview/wv.conf (see CONFIG FILE below).

    The same subcommands work in both modes.

INSTALLATION (production server)
    The minimum a server needs is:

      /usr/local/bin/wv                # the dispatcher (this script)
      /usr/local/lib/wv-lib/           # subcommand implementations
      /etc/wealthview/wv.conf          # paths + project name + remote host
      /etc/wealthview/.env             # secrets (DB_PASSWORD, JWT_SECRET, ...)
      /etc/wealthview/docker-compose.prod.yml
      /var/lib/wealthview/backups/     # backup output directory

    Recommended install sequence on a fresh host:

      # 1. Lay down the binaries (from your source checkout or release tar).
      sudo install -m 0755 bin/wv /usr/local/bin/wv
      sudo install -d -m 0755 /usr/local/lib/wv-lib
      sudo install -m 0644 bin/wv-lib/*.sh /usr/local/lib/wv-lib/
      sudo ln -snf /usr/local/lib/wv-lib /usr/local/bin/wv-lib
      # (the dispatcher locates wv-lib next to itself, hence the symlink)

      # 2. Lay down the config.
      sudo install -d -m 0755 /etc/wealthview
      sudo cp docker-compose.prod.yml /etc/wealthview/
      sudo cp .env.example /etc/wealthview/.env
      sudo chmod 0600 /etc/wealthview/.env
      sudo install -d -m 0755 /var/lib/wealthview/backups

      # 3. Write the config file (see CONFIG FILE below for the schema).
      sudo $EDITOR /etc/wealthview/wv.conf

      # 4. Verify everything resolves.
      sudo wv config-check

      # 5. Bring the stack up.
      sudo wv up

CONFIG FILE
    Format: shell-syntax KEY=VALUE lines. No executable logic — wv refuses
    to load a file containing anything else. NEVER put real secrets here;
    secrets belong in the env file (WV_ENV_FILE) which the compose stack
    reads directly.

    Resolution order (first match wins):
      1. --config FILE on the command line
      2. $WV_CONFIG_FILE environment variable
      3. /etc/wealthview/wv.conf
      4. $XDG_CONFIG_HOME/wealthview/wv.conf
      5. ~/.config/wealthview/wv.conf
      6. Source-tree fallback (when bin/wv lives next to docker-compose.yml)

    Recognised keys (all optional; sensible defaults apply):
      WV_COMPOSE_FILE           Absolute path to the compose file.
      WV_COMPOSE_OVERRIDE_FILE  Optional second -f for docker compose.
      WV_ENV_FILE               Absolute path to the env file (.env).
      WV_BACKUPS_DIR            Absolute path to the backups directory.
      WV_COMPOSE_PROJECT        docker compose project name (default: wealthview).
      WV_HOST                   user@host for remote operation. When set,
                                wv runs docker against DOCKER_HOST=ssh://...
                                You need ssh-agent with an authorised key.
      WV_APP_PORT               Host port the app is bound to (default 80).
      WV_HEALTH_URL             Full health URL. Overrides the default
                                http://<host>:<WV_APP_PORT>/actuator/health.
      WV_PREVIOUS_IMAGE_FILE    Where `wv update` records the prior tag for
                                `wv rollback` to consume.

    Example /etc/wealthview/wv.conf:
        WV_COMPOSE_FILE=/etc/wealthview/docker-compose.prod.yml
        WV_ENV_FILE=/etc/wealthview/.env
        WV_BACKUPS_DIR=/var/lib/wealthview/backups
        WV_COMPOSE_PROJECT=wealthview
        WV_APP_PORT=80

CONTAINER DEPLOYMENT FLOW
    WealthView runs as a small Docker Compose stack: one app container
    (Spring Boot), one db container (PostgreSQL), and optionally a backup
    container for nightly off-host dumps. The compose file pins the app
    image as ${WEALTHVIEW_IMAGE}:${WEALTHVIEW_VERSION} — both live in the
    env file and drive `wv update` and `wv rollback`.

    In production the app image is PULLED, not built here. CI publishes it
    to ghcr.io/<owner>/wealthview on every v* tag, after the unit tests,
    quality gates and full integration suite pass, so what reaches the
    server is an artifact that was actually verified. The host needs
    neither the source tree nor a JDK. WEALTHVIEW_IMAGE only needs setting
    to point at a fork or a private mirror.

    The GHCR package is public, so this host pulls it with no credentials
    and no registry setup. Only a private fork or mirror requires a
    `docker login ghcr.io` with a read:packages token before `wv update`
    can pull.

    Upgrade lifecycle:
      1. Set WEALTHVIEW_VERSION=<new tag> in $WV_ENV_FILE.
      2. Run `wv update`. It will:
           a. Validate the env file.
           b. Take a pre-update backup (labelled `pre-update`).
           c. Record the currently-running image tag for rollback.
           d. (Unless --no-pull) pull the app image.
           e. Recreate the app container with the new image.
           f. Poll the health endpoint for up to 3 minutes.
           g. On health failure: auto-rollback to the recorded tag.
      3. On success, the new tag is live. On failure, the old tag is
         restored AND the pre-update backup is still on disk for manual
         recovery if a database restore is also needed.

    Rolling back manually: `wv rollback` reverts the app image. If the
    new version applied schema changes you also need to restore the
    pre-update DB dump — see `wv backups` and `wv restore`.

    First-time install on a fresh host: `wv up` brings the stack up
    against an empty database; Flyway migrations run on first boot. To
    seed from an existing host's data, do `wv migrate-out` on the source
    host then `wv migrate-in <bundle>` on the new one.

REMOTE OPERATION
    Set WV_HOST in wv.conf (or pass --host user@host) to drive a remote
    Docker daemon over SSH. Mechanism: wv sets DOCKER_HOST=ssh://$WV_HOST
    on each docker compose invocation.

    Requirements on the operator's machine:
      - ssh-agent running with a key authorised on the remote host
      - the SSH key present in ~/.ssh/known_hosts (or strict host checking
        configured appropriately)
      - the docker CLI installed locally; the docker daemon does NOT need
        to be running locally
      - the same wv.conf points at the remote host's WV_COMPOSE_FILE
        path (i.e. paths inside wv.conf refer to the remote filesystem)

    Backup files (`wv backup`) still land in $WV_BACKUPS_DIR on the
    operator's local machine because pg_dump streams via `docker
    compose exec`. To produce a backup that sits on the remote host
    instead, run wv from a shell on that host.

GLOBAL FLAGS
    --config FILE       Use FILE as the config file for this invocation.
    --host USER@HOST    Treat the remote host as the docker target
                        (DOCKER_HOST=ssh://USER@HOST).
    -h, --help          On a subcommand, print its detailed help.

ENVIRONMENT VARIABLES
    WV_CONFIG_FILE      Same as --config; honoured if set.
    WV_HOST             Same as --host; honoured if set.
    WV_ASSUME_YES=1     Skip all interactive confirmation prompts (use
                        for cron and other non-interactive callers).

SUBCOMMANDS

    Lifecycle
      up                  Start the stack (idempotent).
                          --no-build / --no-detach / --no-wait
      down                Stop the stack; data volumes are preserved.
      restart             wv down && wv up. Flags after `restart` are
                          forwarded to `up`.
      status              Container status + one-shot health probe.
      logs [service]      Tail logs (default: all services). --tail N.

    Backup / restore
      backup              pg_dump to $WV_BACKUPS_DIR/<timestamp>.dump.
                          --encrypt   age-encrypt (needs
                                      BACKUP_ENCRYPTION_RECIPIENT)
                          --remote    also copy to BACKUP_REMOTE_DEST
                          --label TEXT  append a label to the filename
                          --dry-run   show what would happen
      backups             List backups with size + age.
      list-backups        Alias for `backups`.
      restore <file>      pg_restore from <file>. Prompts; --dry-run.
                          Auto-decrypts .age files (needs
                          BACKUP_ENCRYPTION_KEY_FILE).
      verify <file>       Round-trip <file> through a throwaway pg
                          container — proves it can actually be restored.

    Updates
      update              Pre-update backup -> pull -> swap -> health
                          check -> auto-rollback on failure.
                          --no-rollback / --no-pull / --build
      rollback            Revert app to the image tag saved by the last
                          successful `wv update`.

    Host migration
      migrate-out         Build a portable bundle (encrypted backup +
                          .env skeleton + version pin).
                          --dest DIR / --no-encrypt
      migrate-in <bundle> Decrypt the bundle on a fresh host, write a
                          skeleton .env, restore the database.

    Operational
      psql                Open a psql shell in the running db container.
      rotate-secret NAME  Generate a new secure value for one of:
                          JWT_SECRET, DB_PASSWORD, SUPER_ADMIN_PASSWORD.
                          MFA_ENCRYPTION_KEY is deliberately NOT rotatable:
                          it decrypts stored TOTP secrets at rest, so
                          replacing it would lock every MFA user out.
      config-check        Validate the resolved config: wv.conf,
                          env file, compose files, required tools.

EXAMPLES
    Bring up the dev stack:
        ./wv up
        ./wv status

    Take and verify a backup, then restore it:
        wv backup --encrypt --label pre-experiment
        wv backups
        wv verify backups/wealthview_2026-05-13T...dump.age
        wv restore backups/wealthview_2026-05-13T...dump.age

    Upgrade in place:
        # Edit /etc/wealthview/.env: WEALTHVIEW_VERSION=1.4.0
        wv update

    Drive a remote host from your laptop:
        wv --host root@wealthview.example.com status
        wv --host root@wealthview.example.com update

    Move the deployment to another host:
        # On the source host:
        wv migrate-out
        # On the destination host (binaries + wv.conf already in place):
        scp source-host:.../backups/migrations/wealthview-bundle-*.tar.gz .
        wv migrate-in wealthview-bundle-*.tar.gz

For per-subcommand details, run:
    wv <subcommand> --help
EOH
}
