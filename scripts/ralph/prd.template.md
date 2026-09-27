# Product Requirements Document (PRD)

This file defines tasks for Ralph to complete autonomously.

## How to Use

1. Copy this template to your project as `prd.md`
2. Define your tasks following the format below
3. Run `ralph.sh` to start autonomous execution
4. Ralph will complete tasks until all have `Passes: true`

---

## Task: Example - Add health check endpoint
- **Category**: Backend
- **Description**: Add a `/health` endpoint that returns `{"status": "ok"}`
- **Validation**:
  - [ ] `curl -s localhost:8080/health | jq -e '.status == "ok"'` - Health endpoint returns ok
  - [ ] `npm test` - All tests pass
- **Passes**: false

## Task: Example - Add request logging
- **Category**: Backend
- **Description**: Add middleware to log all incoming HTTP requests with method, path, and duration
- **Validation**:
  - [ ] `curl localhost:8080/health && grep -q "GET /health" logs/app.log` - Requests are logged
  - [ ] `npm test` - All tests pass
- **Passes**: false

## Task: Example - Update README
- **Category**: Documentation
- **Description**: Add API documentation section to README.md listing all endpoints
- **Validation**:
  - [ ] `grep -q "## API" README.md` - API section exists
  - [ ] `grep -q "/health" README.md` - Health endpoint documented
- **Passes**: false

---

## Writing Good Tasks

### Do

- Be specific about what needs to be implemented
- Include concrete validation commands that can be automated
- Use commands that return non-zero on failure
- Keep tasks atomic (one feature/fix per task)

### Don't

- Use vague descriptions like "improve performance"
- Skip validation steps
- Create tasks that depend on manual verification
- Make tasks too large (break them down)

### Validation Command Tips

```bash
# Check file exists
test -f path/to/file

# Check content in file
grep -q "expected content" file.txt

# Check JSON response
curl -s url | jq -e '.field == "value"'

# Run tests
npm test
mvn test
pnpm test:unit

# Check command succeeds
some-command && echo "ok"
```
