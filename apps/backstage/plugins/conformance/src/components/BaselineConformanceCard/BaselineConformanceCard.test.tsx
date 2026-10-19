import { Entity } from '@backstage/catalog-model';
import { discoveryApiRef, fetchApiRef } from '@backstage/frontend-plugin-api';
import { mockApis, renderInTestApp } from '@backstage/frontend-test-utils';
import { EntityProvider } from '@backstage/plugin-catalog-react';
import { screen } from '@testing-library/react';
import { EntityBaselineConformanceCard } from './BaselineConformanceCard';

const document = {
  generated_at: '2026-10-19T03:00:00Z',
  results: [
    {
      repository: 'example-org/one',
      check: 'conformance',
      exit_code: 0,
      timestamp: '2026-10-19T02:00:00Z',
    },
    {
      repository: 'example-org/one',
      check: 'agent',
      exit_code: 3,
      timestamp: '2026-10-19T02:05:00Z',
      deviation_adrs: ['https://example.com/docs/adr/0007-a-deviation.md'],
    },
    {
      repository: 'example-org/two',
      check: 'agent',
      exit_code: 2,
      timestamp: '2026-10-19T02:10:00Z',
    },
  ],
};

function entityFor(slug?: string): Entity {
  return {
    apiVersion: 'backstage.io/v1alpha1',
    kind: 'Component',
    metadata: {
      name: 'one',
      annotations: slug ? { 'github.com/project-slug': slug } : {},
    },
    spec: { type: 'service', owner: 'me', lifecycle: 'production' },
  };
}

async function renderCard(entity: Entity) {
  const fetch = jest.fn().mockResolvedValue(
    new Response(JSON.stringify(document), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }),
  );
  await renderInTestApp(
    <EntityProvider entity={entity}>
      <EntityBaselineConformanceCard />
    </EntityProvider>,
    {
      apis: [
        [fetchApiRef, { fetch }],
        [discoveryApiRef, mockApis.discovery({ baseUrl: 'http://backend' })],
      ],
    },
  );
  return fetch;
}

describe('EntityBaselineConformanceCard', () => {
  it('shows one row per check of the entity repository', async () => {
    const fetch = await renderCard(entityFor('example-org/one'));
    expect(await screen.findByText('conformance')).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith(
      'http://backend/api/conformance/conformance.json',
    );
    expect(screen.getByText('agent')).toBeInTheDocument();
    expect(screen.getByText('clean')).toBeInTheDocument();
    expect(screen.getByText('drift')).toBeInTheDocument();
    expect(screen.queryByText('check failed')).not.toBeInTheDocument();
    expect(screen.getByText('0007-a-deviation')).toHaveAttribute(
      'href',
      'https://example.com/docs/adr/0007-a-deviation.md',
    );
  });

  it('shows the empty state for a repository without results', async () => {
    await renderCard(entityFor('example-org/three'));
    expect(
      await screen.findByText('No conformance data for this component'),
    ).toBeInTheDocument();
    expect(
      screen.getByText('conformance.json generated 2026-10-19T03:00:00Z'),
    ).toBeInTheDocument();
  });

  it('shows the empty state for an entity without a project slug', async () => {
    await renderCard(entityFor());
    expect(
      await screen.findByText('No conformance data for this component'),
    ).toBeInTheDocument();
  });
});
