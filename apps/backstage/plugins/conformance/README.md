# conformance

The frontend half of the Baseline conformance card. It adds the entity card
`EntityBaselineConformanceCard` to the overview page of every Component (filter
`{ kind: 'component' }`). The data contract and the card are described in the app's
[README](../../README.md#baseline-conformance-card).

| File | What it is |
|---|---|
| `src/plugin.tsx` | The plugin and its `EntityCardBlueprint` extension |
| `src/components/BaselineConformanceCard/` | The card: reads `/api/conformance/conformance.json` with `fetchApiRef` and `discoveryApiRef` |
| `src/status.ts` | Exit code to status: 0 clean, 3 drift, anything else check failed |
| `src/types.ts` | The document's types and the selection by repository |
