#!/usr/bin/env bash
#
# One-shot state migration for the clippy -> klippy resource-label rename.
#
# WHY THIS EXISTS
#   The rename changed Terraform resource *labels* only (azurerm_x.clippy ->
#   azurerm_x.klippy). Azure resource names are untouched; nothing in the cloud
#   changes. But a label IS the address Terraform tracks state under, so until
#   the state is moved, Terraform reads the old addresses as deleted and the new
#   ones as absent -- and plans to DESTROY and RECREATE your database, storage
#   account and VM.
#
#   Run this before the first apply after pulling the rename. It is idempotent:
#   already-moved resources are skipped, so re-running is safe.
#
# USAGE
#   cd apps/klippy/devops && ./migrate-state-klippy-rename.sh
#
set -euo pipefail

cd "$(dirname "$0")"

if ! command -v terraform >/dev/null 2>&1; then
    echo "terraform not on PATH." >&2
    exit 1
fi

RESOURCES=(
    azurerm_resource_group
    azurerm_virtual_network
    azurerm_postgresql_flexible_server
    azurerm_postgresql_flexible_server_database
    azurerm_storage_account
    azurerm_storage_container
)

echo "==> Reading current state"
STATE="$(terraform state list 2>/dev/null || true)"

if [ -z "$STATE" ]; then
    echo "State is empty or uninitialised. Nothing to migrate."
    echo "(If this is a fresh workspace, the rename needs no migration -- just apply.)"
    exit 0
fi

moved=0
skipped=0

for type in "${RESOURCES[@]}"; do
    old="${type}.clippy"
    new="${type}.klippy"

    if grep -qx -- "$new" <<<"$STATE"; then
        echo "  skip  ${new} (already migrated)"
        skipped=$((skipped + 1))
        continue
    fi

    if ! grep -qx -- "$old" <<<"$STATE"; then
        echo "  skip  ${old} (not in state)"
        skipped=$((skipped + 1))
        continue
    fi

    echo "  move  ${old} -> ${new}"
    terraform state mv "$old" "$new"
    moved=$((moved + 1))
done

echo
echo "==> Moved ${moved}, skipped ${skipped}"
echo
echo "==> Verifying with a plan"
echo

# The gate. A correct migration is a no-op plan; anything else means a resource
# was missed and applying would destroy it.
if terraform plan -detailed-exitcode -input=false; then
    echo
    echo "PASS: plan is clean. The rename is address-only, as intended."
    exit 0
fi

status=$?
if [ "$status" -eq 2 ]; then
    echo
    echo "STOP: the plan proposes changes."
    echo
    echo "An address-only rename must plan to NO changes. If the plan above"
    echo "proposes destroying or recreating anything, do NOT apply it -- a"
    echo "resource is still tracked under its old address, and applying will"
    echo "take the real resource down with it."
    echo
    echo "Compare 'terraform state list' against the labels in *.tf and move"
    echo "whatever is missing before going further."
    exit 2
fi

echo "terraform plan failed (exit ${status})." >&2
exit "$status"
