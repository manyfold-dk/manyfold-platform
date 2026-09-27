import type { App } from '@slack/bolt';
import { registerStatusCommand } from './status.js';
import { registerHealthCommand } from './health.js';
import { registerAlertsCommand } from './alerts.js';
import { registerRemediateCommand } from './remediate.js';

export function registerCommands(app: App): void {
  registerStatusCommand(app);
  registerHealthCommand(app);
  registerAlertsCommand(app);
  registerRemediateCommand(app);
}
