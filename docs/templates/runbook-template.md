# Procedure Name

<!--
To use this template:
1. Copy to appropriate docs/runbooks/ subdirectory
2. Rename to descriptive-name.md
3. Fill in all sections
4. Update docs/runbooks/README.md index
5. Update ToC if adding new sections
-->

Brief description of what this procedure accomplishes and when to use it.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Pre-Procedure Checklist](#pre-procedure-checklist)
- [Procedure](#procedure)
- [Verification](#verification)
- [Rollback](#rollback)
- [Troubleshooting](#troubleshooting)
- [References](#references)

## Prerequisites

- [ ] Required access or permissions
- [ ] Required tools installed
- [ ] Required configuration in place

## Pre-Procedure Checklist

<!-- Verification steps before starting -->

- [ ] Verified current state
- [ ] Confirmed no conflicting operations in progress
- [ ] Notified relevant stakeholders (if applicable)

## Procedure

### Step 1: Description

Explain what this step does.

```bash
# Command to execute
command --with-flags
```

Expected output or result.

### Step 2: Description

Explain what this step does.

```bash
# Command to execute
command --with-flags
```

### Step 3: Description

Continue as needed...

## Verification

<!-- How to confirm the procedure succeeded -->

```bash
# Verification command
command --status
```

Expected output indicating success:

```
Expected output here
```

## Rollback

<!-- How to undo if something goes wrong -->

If the procedure fails or needs to be reversed:

```bash
# Rollback command
command --undo
```

## Troubleshooting

### Common Issue 1

**Symptom**: Description of what you observe.

**Cause**: Why this happens.

**Solution**: How to fix it.

```bash
# Fix command
```

### Common Issue 2

Continue as needed...

## References

- [Related Runbook](link)
- [Relevant ADR](link)
- [External Documentation](link)
