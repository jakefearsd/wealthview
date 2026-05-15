# shellcheck shell=bash
# help.sh — top-level help and per-subcommand --help dispatch.

wv_help() {
    cat <<'EOH'
wv — WealthView admin command surface.

Usage:
    ./wv <subcommand> [options]
    ./wv <subcommand> --help

Environment mode:
    The same subcommands work for both dev and prod stacks. Mode is detected
    from .env: if WEALTHVIEW_VERSION is set the prod compose file is used,
    otherwise the default dev compose file. Override by editing .env.

Subcommands

    Lifecycle
      up                  Start the stack (idempotent).
      down                Stop the stack; data volumes are preserved.
      status              Show container status and a one-shot health probe.
      logs [service]      Tail logs (default: all services). Use --tail N to limit.

    Backup / restore
      backup              pg_dump to ./backups/<timestamp>.dump.
                          --encrypt        encrypt with age (requires
                                           BACKUP_ENCRYPTION_RECIPIENT env var)
                          --remote         also copy to BACKUP_REMOTE_DEST if set
                          --label TEXT     append a label to the filename
      backups             List backups in ./backups/ with size and age.
      restore <file>      pg_restore from <file>. Prompts for confirmation.
                          Auto-decrypts .age files (needs BACKUP_ENCRYPTION_KEY_FILE).
      verify <file>       Round-trip <file> through a throwaway pg container.
                          Confirms the dump can actually be restored.

    Updates
      update              Pre-update backup -> rebuild image -> swap -> health
                          check -> auto-rollback on failure.
                          --no-rollback    leave failed deployment running for
                                           inspection
                          --no-build       reuse existing image (used when
                                           WEALTHVIEW_VERSION already pinned)
      rollback            Restart with the previous image tag pinned by the
                          last successful update.

    Host migration
      migrate-out         Build a portable bundle (encrypted backup +
                          .env.example skeleton + version pin) under
                          backups/migrations/.
      migrate-in <bundle> Decrypt a bundle on a fresh host, write skeleton
                          .env, restore database. Stack must already be up.

    Operational
      psql                Open a psql shell in the running db container.
      rotate-secret NAME  Generate a new secure value for one of:
                          JWT_SECRET, DB_PASSWORD, SUPER_ADMIN_PASSWORD.
      config-check        Validate .env, scan for known-bad values, validate
                          compose files, list missing tools.

    Help
      help                This message.

Global flags
      WV_ASSUME_YES=1     Skip all confirmation prompts (for automation).

Examples
    ./wv up
    ./wv backup --encrypt --label pre-experiment
    ./wv update
    ./wv migrate-out
    ./wv migrate-in backups/migrations/wealthview-bundle-2026-05-09.tar.gz

For per-subcommand details, run:
    ./wv <subcommand> --help
EOH
}
