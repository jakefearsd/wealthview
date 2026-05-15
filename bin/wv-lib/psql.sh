# shellcheck shell=bash
# psql.sh — open a psql shell against the running db container.

wv_psql() {
    while (( $# > 0 )); do
        case "$1" in
            -h|--help)
                cat <<'USAGE'
./wv psql — open an interactive psql shell against the running db container.

The shell connects as wv_app to the wealthview database.
USAGE
                return 0
                ;;
            *) wv_die "Unknown option for 'psql': $1" ;;
        esac
    done

    wv_compose exec db psql -U wv_app wealthview
}
