# Contributing to Aria Conductor

Thank you for your interest in contributing! This document provides guidelines and information for contributors.

## Code of Conduct

Please read our [Code of Conduct](CODE_OF_CONDUCT.md) before contributing.

## How to Contribute

### Reporting Bugs

1. Check existing issues to avoid duplicates
2. Use the bug report template
3. Include steps to reproduce, expected behavior, and actual behavior

### Suggesting Features

1. Check existing issues and discussions
2. Use the feature request template
3. Describe the problem your feature solves

### Submitting Pull Requests

1. Fork the repository
2. Create a feature branch from `main`
3. Make your changes with clear, descriptive commits
4. Ensure all tests pass: `mvn clean test`
5. Submit a pull request using the PR template
6. Wait for review and address any feedback

## Development

### Setup

See the [README](README.md) for development setup instructions.

### Code Style

- Java: Follow existing code conventions (no enforced formatter yet)
- TypeScript/React: Prettier + ESLint (configured in project)
- Python: Follow PEP 8

### Testing

- Run Java tests: `cd agent-control-tower && mvn test`
- Run frontend build check: `cd agent-control-tower/act-dashboard && pnpm build`
- Run Python tests: `cd langchain-adk && pytest`

### Commit Messages

Use conventional commits:
- `feat:` New features
- `fix:` Bug fixes
- `docs:` Documentation changes
- `refactor:` Code refactoring
- `test:` Test changes
- `chore:` Maintenance tasks

## License

By contributing, you agree that your contributions will be licensed under the MIT License.