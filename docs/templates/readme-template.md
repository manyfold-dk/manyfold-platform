# Component Name

<!--
To use this template:
1. Copy to the component's root directory
2. Rename to README.md
3. Fill in all relevant sections
4. Remove sections that don't apply
5. Update ToC to match final sections
-->

Brief description of what this component does and its purpose in the system.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Structure](#structure)
- [Configuration](#configuration)
- [Usage](#usage)
- [Architecture](#architecture)
- [Development](#development)
- [Troubleshooting](#troubleshooting)
- [Related Documentation](#related-documentation)

## Prerequisites

- Tool name >= version
- Configuration requirement
- Access or permissions needed

## Quick Start

```bash
# Setup command
./setup.sh

# Start the component
./start.sh
```

## Structure

```
component/
├── src/           # Source code
├── config/        # Configuration files
├── scripts/       # Utility scripts
└── README.md      # This file
```

## Configuration

| Variable | Description | Default |
|----------|-------------|---------|
| `CONFIG_VAR` | Description of what it controls | `default-value` |

### Configuration File

Location: `config/settings.yaml`

```yaml
# Example configuration
setting:
  key: value
```

## Usage

### Common Task 1

Description of the task.

```bash
# Command to perform task
command --flags
```

### Common Task 2

Description of the task.

```bash
# Command to perform task
command --flags
```

## Architecture

<!-- Optional: Describe how the component works internally -->

Brief explanation of internal architecture or design.

```
┌─────────┐     ┌─────────┐
│ Input   │────▶│ Process │────▶ Output
└─────────┘     └─────────┘
```

## Development

### Running Tests

```bash
# Run tests
./test.sh
```

### Building

```bash
# Build the component
./build.sh
```

## Troubleshooting

### Issue Description

**Symptom**: What you observe.

**Solution**: How to fix it.

## Related Documentation

- [Relevant ADR](../docs/adr/0001-topic.md)
- [Related Component](../other-component/README.md)
