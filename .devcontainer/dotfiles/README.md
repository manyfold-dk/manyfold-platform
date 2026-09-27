# Dotfiles Directory

This directory contains your shell configuration files that will be copied into the devcontainer.

## Table of Contents

- [Files](#files)
- [How It Works](#how-it-works)
- [Updating Your Config](#updating-your-config)
- [Git Tracking](#git-tracking)

## Files

- **zshrc.template** - Your .zshrc configuration
- **zprofile.template** - Your .zprofile configuration
- **p10k.zsh** - Powerlevel10k theme configuration

## How It Works

1. During container build, oh-my-zsh, Powerlevel10k, and zsh-autosuggestions are installed
2. After container creation, `setup-shell.sh` copies these files to `/home/vscode/`
3. Your terminal in VS Code will use zsh with your custom configuration

## Updating Your Config

If you update your shell configuration on your Mac and want to apply it to the devcontainer:

1. Copy your updated files:
   ```bash
   cp ~/.zshrc .devcontainer/dotfiles/zshrc.template
   cp ~/.zprofile .devcontainer/dotfiles/zprofile.template
   cp ~/.p10k.zsh .devcontainer/dotfiles/p10k.zsh
   ```

2. Rebuild the container:
   - Press `Cmd+Shift+P`
   - Type "Dev Containers: Rebuild Container"

## Git Tracking

These files are tracked in Git so that anyone cloning the repository can have the same shell experience. If you have sensitive information in your shell config (API keys, tokens), make sure to remove them from these templates before committing.
