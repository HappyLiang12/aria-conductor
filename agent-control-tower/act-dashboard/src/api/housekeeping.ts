import client from './client';

/** One leftover item in a category preview. */
export interface HousekeepingCategoryItem {
  id: string;
  title: string;
  status: string;
  age: string;
}

export interface HousekeepingCategorySummary {
  key: 'runs' | 'stuck' | 'kanban' | 'agents' | 'approvals' | string;
  count: number;
  preview: HousekeepingCategoryItem[];
}

export interface HousekeepingScanResult {
  categories: HousekeepingCategorySummary[];
  scannedAt: string;
}

export interface HousekeepingExclusions {
  runIds?: string[];
  kanbanItemIds?: string[];
  agentIds?: string[];
  approvalIds?: string[];
}

export interface HousekeepingExecuteRequest {
  categories: string[];
  includeStuck?: boolean;
  exclusions?: HousekeepingExclusions;
  confirm: boolean;
}

export interface HousekeepingCategoryReceipt {
  key: string;
  cleared: number;
  failed: number;
  skipped: number;
}

export interface HousekeepingReceipt {
  categories: HousekeepingCategoryReceipt[];
  executedAt: string;
}

const BASE = '/api/v1/housekeeping';

export async function scanHousekeeping(includeStuck = true): Promise<HousekeepingScanResult> {
  const { data } = await client.get<HousekeepingScanResult>(`${BASE}/scan`, {
    params: { includeStuck },
  });
  return data;
}

export async function executeHousekeeping(
  request: HousekeepingExecuteRequest
): Promise<HousekeepingReceipt> {
  const { data } = await client.post<HousekeepingReceipt>(`${BASE}/execute`, request);
  return data;
}
