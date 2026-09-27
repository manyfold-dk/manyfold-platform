import type { KnownBlock } from '@slack/types';

export function formatRemediationRequest(
  action: string,
  target: string,
  namespace: string,
  requestedBy: string,
  approvalId: string,
): KnownBlock[] {
  return [
    {
      type: 'header',
      text: { type: 'plain_text', text: ':wrench: Remediation Request' },
    },
    { type: 'divider' },
    {
      type: 'section',
      text: {
        type: 'mrkdwn',
        text: [
          `*Action:* ${action}`,
          `*Target:* ${namespace} / ${target}`,
          `*Requested by:* <@${requestedBy}>`,
        ].join('\n'),
      },
    },
    {
      type: 'actions',
      elements: [
        {
          type: 'button',
          text: { type: 'plain_text', text: 'Approve' },
          action_id: 'remediation_approve',
          value: approvalId,
          style: 'primary',
        },
        {
          type: 'button',
          text: { type: 'plain_text', text: 'Reject' },
          action_id: 'remediation_reject',
          value: approvalId,
          style: 'danger',
        },
      ],
    },
  ];
}

export function formatRemediationApproved(
  action: string,
  target: string,
  namespace: string,
  requestedBy: string,
  approvedBy: string,
): KnownBlock[] {
  return [
    {
      type: 'header',
      text: { type: 'plain_text', text: ':white_check_mark: Remediation Approved' },
    },
    { type: 'divider' },
    {
      type: 'section',
      text: {
        type: 'mrkdwn',
        text: [
          `*Action:* ${action}`,
          `*Target:* ${namespace} / ${target}`,
          `*Requested by:* <@${requestedBy}>`,
          `*Approved by:* <@${approvedBy}>`,
        ].join('\n'),
      },
    },
  ];
}

export function formatRemediationRejected(
  action: string,
  target: string,
  namespace: string,
  requestedBy: string,
  rejectedBy: string,
): KnownBlock[] {
  return [
    {
      type: 'header',
      text: { type: 'plain_text', text: ':x: Remediation Rejected' },
    },
    { type: 'divider' },
    {
      type: 'section',
      text: {
        type: 'mrkdwn',
        text: [
          `*Action:* ${action}`,
          `*Target:* ${namespace} / ${target}`,
          `*Requested by:* <@${requestedBy}>`,
          `*Rejected by:* <@${rejectedBy}>`,
        ].join('\n'),
      },
    },
  ];
}

export function formatRemediationExpired(
  action: string,
  target: string,
  namespace: string,
  requestedBy: string,
): KnownBlock[] {
  return [
    {
      type: 'header',
      text: { type: 'plain_text', text: ':hourglass: Remediation Expired' },
    },
    { type: 'divider' },
    {
      type: 'section',
      text: {
        type: 'mrkdwn',
        text: [
          `*Action:* ${action}`,
          `*Target:* ${namespace} / ${target}`,
          `*Requested by:* <@${requestedBy}>`,
          '_No action taken — request expired._',
        ].join('\n'),
      },
    },
  ];
}

export function formatRemediationResult(
  success: boolean,
  action: string,
  target: string,
  namespace: string,
  message: string,
): KnownBlock[] {
  const emoji = success ? ':white_check_mark:' : ':x:';
  const label = success ? 'Success' : 'Failed';

  return [
    {
      type: 'section',
      text: {
        type: 'mrkdwn',
        text: `${emoji} *Remediation ${label}*\n*Action:* ${action}\n*Target:* ${namespace} / ${target}\n${message}`,
      },
    },
  ];
}
