import client from './client';
import { applyOperatorHeaders } from './operatorSession';
import type {
  AmendReportRequest,
  GenerateReportRequest,
  ReportArtifact,
} from '../types';

const BASE = '/api/v1/reports';

// Regenerate and archive are operator-only server-side (403 for a worker, 401
// without a session), so each applies the stored operator session before the
// request. Generate and amend stay ungated on purpose: both have a worker-write
// MCP twin, so a run worker is expected to request them per call.

export async function listReports(): Promise<ReportArtifact[]> {
  const { data } = await client.get<ReportArtifact[]>(BASE);
  return data;
}

export async function getReport(id: string): Promise<ReportArtifact> {
  const { data } = await client.get<ReportArtifact>(`${BASE}/${encodeURIComponent(id)}`);
  return data;
}

export async function generateReport(request: GenerateReportRequest): Promise<ReportArtifact> {
  const { data } = await client.post<ReportArtifact>(`${BASE}/generate`, request);
  return data;
}

export async function amendReport(id: string, request: AmendReportRequest): Promise<ReportArtifact> {
  const { data } = await client.post<ReportArtifact>(
    `${BASE}/${encodeURIComponent(id)}/amend`,
    request
  );
  return data;
}

export async function regenerateReport(id: string): Promise<ReportArtifact> {
  applyOperatorHeaders();
  const { data } = await client.post<ReportArtifact>(
    `${BASE}/${encodeURIComponent(id)}/regenerate`,
    {}
  );
  return data;
}

export async function archiveReport(id: string): Promise<void> {
  applyOperatorHeaders();
  await client.delete(`${BASE}/${encodeURIComponent(id)}`);
}

export function reportHtmlUrl(id: string): string {
  return `${BASE}/${encodeURIComponent(id)}/html`;
}

export async function getReportHtml(id: string): Promise<string> {
  const { data } = await client.get<string>(
    `${BASE}/${encodeURIComponent(id)}/html`,
    { responseType: 'text', transformResponse: [(v) => v] }
  );
  return typeof data === 'string' ? data : String(data ?? '');
}
