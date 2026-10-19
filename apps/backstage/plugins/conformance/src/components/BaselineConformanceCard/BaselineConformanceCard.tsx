import {
  InfoCard,
  Link,
  Progress,
  ResponseErrorPanel,
  StatusError,
  StatusOK,
  StatusWarning,
} from '@backstage/core-components';
import { ResponseError } from '@backstage/errors';
import {
  discoveryApiRef,
  fetchApiRef,
  useApi,
} from '@backstage/frontend-plugin-api';
import { useEntity } from '@backstage/plugin-catalog-react';
import Table from '@material-ui/core/Table';
import TableBody from '@material-ui/core/TableBody';
import TableCell from '@material-ui/core/TableCell';
import TableHead from '@material-ui/core/TableHead';
import TableRow from '@material-ui/core/TableRow';
import Typography from '@material-ui/core/Typography';
import useAsync from 'react-use/esm/useAsync';
import {
  ConformanceStatus,
  statusFromExitCode,
  statusLabels,
} from '../../status';
import {
  ConformanceDocument,
  ConformanceResult,
  resultsForRepository,
} from '../../types';

/** The annotation that names an entity's repository, `<owner>/<name>`. */
export const PROJECT_SLUG_ANNOTATION = 'github.com/project-slug';

const StatusChip = ({ status }: { status: ConformanceStatus }) => {
  const label = statusLabels[status];
  if (status === 'clean') {
    return <StatusOK>{label}</StatusOK>;
  }
  if (status === 'drift') {
    return <StatusWarning>{label}</StatusWarning>;
  }
  return <StatusError>{label}</StatusError>;
};

/** A deviation ADR's link text: its file name without the extension. */
function adrLabel(url: string): string {
  const name = url.split('/').filter(Boolean).pop() ?? url;
  return name.replace(/\.md$/, '');
}

const ResultRow = ({ result }: { result: ConformanceResult }) => (
  <TableRow>
    <TableCell>{result.check}</TableCell>
    <TableCell>
      <StatusChip status={statusFromExitCode(result.exit_code)} />
    </TableCell>
    <TableCell>{result.timestamp}</TableCell>
    <TableCell>
      {(result.deviation_adrs ?? []).map(url => (
        <div key={url}>
          <Link to={url}>{adrLabel(url)}</Link>
        </div>
      ))}
    </TableCell>
  </TableRow>
);

/**
 * The entity's baseline conformance: one row per check that ran against the
 * repository named by its github.com/project-slug annotation.
 */
export const EntityBaselineConformanceCard = () => {
  const { entity } = useEntity();
  const discoveryApi = useApi(discoveryApiRef);
  const fetchApi = useApi(fetchApiRef);
  const repository = entity.metadata.annotations?.[PROJECT_SLUG_ANNOTATION];

  const { value, loading, error } = useAsync(async () => {
    const baseUrl = await discoveryApi.getBaseUrl('conformance');
    const response = await fetchApi.fetch(`${baseUrl}/conformance.json`);
    if (!response.ok) {
      throw await ResponseError.fromResponse(response);
    }
    return (await response.json()) as ConformanceDocument;
  }, [discoveryApi, fetchApi]);

  if (loading) {
    return (
      <InfoCard title="Baseline conformance">
        <Progress />
      </InfoCard>
    );
  }
  if (error || !value) {
    return (
      <InfoCard title="Baseline conformance">
        <ResponseErrorPanel error={error ?? new Error('No conformance data')} />
      </InfoCard>
    );
  }

  const results = resultsForRepository(value, repository);
  const generated = value.generated_at ?? 'an unknown time';
  if (results.length === 0) {
    return (
      <InfoCard title="Baseline conformance">
        <Typography variant="body1">
          No conformance data for this component
        </Typography>
        <Typography variant="body2" color="textSecondary">
          conformance.json generated {generated}
        </Typography>
      </InfoCard>
    );
  }

  return (
    <InfoCard
      title="Baseline conformance"
      subheader={`conformance.json generated ${generated}`}
    >
      <Table size="small" aria-label="Baseline conformance">
        <TableHead>
          <TableRow>
            <TableCell>Check</TableCell>
            <TableCell>Status</TableCell>
            <TableCell>Checked</TableCell>
            <TableCell>Deviation ADRs</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {results.map(result => (
            <ResultRow key={result.check} result={result} />
          ))}
        </TableBody>
      </Table>
    </InfoCard>
  );
};
