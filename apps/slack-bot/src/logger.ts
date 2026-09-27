import pino from 'pino';
import { config } from './config.js';

/** The process logger. Its own module, so importing a handler never starts the app. */
export const logger = pino({ level: config.logLevel });
