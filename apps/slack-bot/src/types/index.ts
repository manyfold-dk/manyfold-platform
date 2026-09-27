export interface AlertEvent {
  fingerprint: string;
  status: 'firing' | 'resolved';
  alertName: string;
  severity: 'critical' | 'warning' | 'info';
  namespace: string;
  pod?: string;
  deployment?: string;
  summary: string;
  timestamp: string;
}

export interface DeploymentEvent {
  pipeline: string;
  runName: string;
  status: 'succeeded' | 'failed' | 'cancelled';
  gitRevision: string;
  gitUrl: string;
  imageTag: string;
  environment: string;
  duration?: string;
  timestamp: string;
}

export interface HealthLayer {
  name: string;
  status: 'healthy' | 'degraded' | 'unhealthy';
  details: string;
}

export interface HealthResponse {
  overall: 'healthy' | 'degraded' | 'unhealthy';
  layers: HealthLayer[];
  activeAlerts: number;
}

export interface HealthSummary {
  overall: 'healthy' | 'degraded' | 'unhealthy';
  infrastructure: string;
  cluster: string;
  platform: string;
  pipelines: string;
  applications: string;
  activeAlerts: number;
}

export interface AlertSummary {
  total: number;
  critical: number;
  warning: number;
  info: number;
}

export interface StoredAlert {
  fingerprint: string;
  alertName: string;
  severity: string;
  namespace: string;
  pod?: string;
  deployment?: string;
  summary: string;
  status: string;
  startsAt: string;
  endsAt?: string;
}

export interface RemediationSummary {
  recentActions: number;
  status: string;
}

export interface PendingApproval {
  id: string;
  action: string;
  target: string;
  namespace: string;
  requestedBy: string;
  channelId: string;
  messageTs: string;
  createdAt: number;
}
