import type { App } from '@slack/bolt';
import { registerApproveAction } from './remediation-approve.js';
import { registerRejectAction } from './remediation-reject.js';
import { registerAlertAcknowledgeAction } from './alert-acknowledge.js';
import { registerOpsApproveAction } from './ops-approve.js';
import { registerOpsRejectAction } from './ops-reject.js';

export function registerActions(app: App): void {
  registerApproveAction(app);
  registerRejectAction(app);
  registerAlertAcknowledgeAction(app);
  registerOpsApproveAction(app);
  registerOpsRejectAction(app);
}
