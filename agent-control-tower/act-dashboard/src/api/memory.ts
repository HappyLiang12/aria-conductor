import client from './client';
import { applyOperatorHeaders } from './operatorSession';

/**
 * The memory operator surface (`MemoryController`, `/api/v1/memory`).
 *
 * Types are defined here rather than in `src/types/index.ts` because this lane
 * does not own that file; every field mirrors the shipped Java view / request
 * record exactly (do not add a field the server does not accept). Reads are
 * deterministic views AND resolve operator authority, like every route on this
 * surface: a refused read is a 401/403 the caller must render, never an empty
 * list. Mutations additionally apply the locally established operator session's
 * CSRF header before the request.
 */

/** `EntityStatus` — PENDING is an extraction's proposal, never memory. */
export type MemoryEntityStatus = 'PENDING' | 'ACTIVE' | 'MERGED' | 'RETIRED';
/** `FactStatus` — EXTRACTION writes land PENDING until an operator decides. */
export type MemoryFactStatus = 'PENDING' | 'ACTIVE' | 'RETIRED';
/** `Sensitivity` (act-common). */
export type MemorySensitivity = 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL' | 'RESTRICTED';
/** `ProposalDecision` — the only two verdicts the decide route accepts. */
export type MemoryProposalDecision = 'APPROVE' | 'REJECT';
/** `MemorySubjectType` — which decision endpoint owns a proposal. */
export type MemorySubjectType = 'ENTITY' | 'RELATION' | 'FACT';

/** `io.aria.conductor.memory.dto.MemoryEntityView`. */
export interface MemoryEntityView {
  id: string;
  kind: string;
  canonicalKey: string;
  displayName: string;
  sensitivity: string | null;
  status: string | null;
  origin: string | null;
  mergedInto: string | null;
  version: number;
  createdAt: string | null;
  updatedAt: string | null;
}

/** `io.aria.conductor.memory.dto.MemoryFactView`. */
export interface MemoryFactView {
  id: string;
  entityId: string;
  factKey: string;
  valueText: string;
  valueType: string | null;
  status: string | null;
  origin: string | null;
  sensitivity: string | null;
  confidence: number | null;
  validFrom: string | null;
  validTo: string | null;
  version: number;
}

/** `io.aria.conductor.memory.dto.MemoryProposalView`. */
export interface MemoryProposalView {
  id: string;
  subjectType: string;
  entityId: string | null;
  factKey: string | null;
  valueText: string | null;
  predicate: string | null;
  toEntityId: string | null;
  status: string | null;
  origin: string | null;
  sensitivity: string | null;
  confidence: number | null;
  createdAt: string | null;
  validFrom: string | null;
}

/** `MemoryRelationView`. */
export interface MemoryRelationView {
  id: string;
  fromEntityId: string;
  predicate: string;
  toEntityId: string;
  status: string | null;
  origin: string | null;
  sensitivity: string | null;
  confidence: number | null;
  validFrom: string | null;
  validTo: string | null;
}

/** `MemoryKnowledgeLinkView`. */
export interface MemoryKnowledgeLinkView {
  id: string;
  entityId: string;
  knowledgeItemId: string;
  status: string | null;
  createdAt: string | null;
  decidedAt: string | null;
  decidedBy: string | null;
}

/** `MemoryAssertionView` — one row of the append-only audit timeline. */
export interface MemoryAssertionView {
  id: string;
  subjectType: string;
  subjectId: string;
  action: string;
  actorType: string | null;
  actorRef: string | null;
  sourceRef: string | null;
  beforeJson: string | null;
  afterJson: string | null;
  createdAt: string | null;
}

/** `MemoryEntityDetail` — the entity plus its facts, relations, links and timeline. */
export interface MemoryEntityDetail {
  entity: MemoryEntityView;
  facts: MemoryFactView[];
  relations: MemoryRelationView[];
  links: MemoryKnowledgeLinkView[];
  assertions: MemoryAssertionView[];
}

/** `MemoryRecallPreview` — the ranked slice a prompt would receive. */
export interface MemoryRecallPreview {
  entities: MemoryEntityView[];
  facts: MemoryFactView[];
  truncated: boolean;
}

/** `MemoryManifestSection` — whitelisted metadata; deliberately has no content. */
export interface MemoryManifestSection {
  id: string;
  sources: string[];
  chars: number;
  tokens: number;
  truncated: boolean;
}

/** `MemoryManifestView` — one turn's context assembly metadata, never the prompt. */
export interface MemoryManifestView {
  id: string;
  runId: string;
  turnNumber: number;
  conversationId: string | null;
  agentId: string | null;
  providerId: string | null;
  adapter: string | null;
  maxChars: number;
  estimatedTokens: number;
  createdAt: string | null;
  sections: MemoryManifestSection[];
  dropped: string[];
  degraded: string[];
}

/** `CreateEntityRequest`. */
export interface CreateMemoryEntityRequest {
  kind: string;
  canonicalKey: string;
  displayName: string;
  sensitivity: MemorySensitivity;
}

/** `PatchEntityRequest` — exactly one operation per call. */
export interface PatchMemoryEntityRequest {
  displayName?: string;
  aliases?: string[];
  mergeInto?: string;
  retire?: boolean;
}

/** `AddFactRequest`. */
export interface AddMemoryFactRequest {
  factKey: string;
  valueText: string;
  valueType?: string;
  sensitivity: MemorySensitivity;
  sourceRef?: string;
}

/** `SupersedeFactRequest`. */
export interface SupersedeMemoryFactRequest {
  valueText: string;
  expectedVersion: number;
  sourceRef?: string;
}

/** `RetireFactRequest` — optional: the route accepts an absent body. */
export interface RetireMemoryFactRequest {
  sourceRef?: string;
}

export interface ListMemoryEntitiesParams {
  kind?: string;
  q?: string;
  status?: MemoryEntityStatus;
  limit?: number;
}

/** `GET /entities` (status is a query param). */
export async function listMemoryEntities(
  params: ListMemoryEntitiesParams = {},
): Promise<MemoryEntityView[]> {
  const { data } = await client.get<MemoryEntityView[]>('/api/v1/memory/entities', {
    params: buildParams(params),
  });
  return data;
}

/** `POST /entities`. */
export async function createMemoryEntity(
  body: CreateMemoryEntityRequest,
): Promise<MemoryEntityView> {
  applyOperatorHeaders();
  const { data } = await client.post<MemoryEntityView>('/api/v1/memory/entities', body);
  return data;
}

/**
 * `GET /entities/{id}` — the fail-closed default read (ACTIVE-only), or, with
 * `'PENDING'`, the operator-authorised review read of a pending entity
 * (spec :309-313: the operator cannot approve what they cannot see). The server
 * honours no other status value here, so the argument is typed to the only one
 * it does. When it is absent the request carries no params at all: widening the
 * default read would break a security boundary, not just a test.
 */
export async function getMemoryEntity(
  id: string,
  reviewStatus?: 'PENDING',
): Promise<MemoryEntityDetail> {
  const path = `/api/v1/memory/entities/${id}`;
  const { data } =
    reviewStatus === 'PENDING'
      ? await client.get<MemoryEntityDetail>(path, { params: { status: 'PENDING' } })
      : await client.get<MemoryEntityDetail>(path);
  return data;
}

/** `PATCH /entities/{id}` — rename, alias, merge or retire (exactly one). */
export async function patchMemoryEntity(
  id: string,
  body: PatchMemoryEntityRequest,
): Promise<MemoryEntityView> {
  applyOperatorHeaders();
  const { data } = await client.patch<MemoryEntityView>(`/api/v1/memory/entities/${id}`, body);
  return data;
}

/** `POST /entities/{id}/facts`. */
export async function addMemoryFact(
  entityId: string,
  body: AddMemoryFactRequest,
): Promise<MemoryFactView> {
  applyOperatorHeaders();
  const { data } = await client.post<MemoryFactView>(
    `/api/v1/memory/entities/${entityId}/facts`,
    body,
  );
  return data;
}

/** `POST /facts/{id}/supersede` (a POST, not a PATCH) — `id` is the fact id. */
export async function supersedeMemoryFact(
  factId: string,
  body: SupersedeMemoryFactRequest,
): Promise<MemoryFactView> {
  applyOperatorHeaders();
  const { data } = await client.post<MemoryFactView>(
    `/api/v1/memory/facts/${factId}/supersede`,
    body,
  );
  return data;
}

/** `POST /facts/{id}/retire` — a POST with an optional `RetireFactRequest`, not a DELETE. */
export async function retireMemoryFact(
  factId: string,
  body: RetireMemoryFactRequest = {},
): Promise<MemoryFactView> {
  applyOperatorHeaders();
  const { data } = await client.post<MemoryFactView>(
    `/api/v1/memory/facts/${factId}/retire`,
    body,
  );
  return data;
}

/**
 * `GET /proposals?status=` — PENDING is the inbox the operator decides.
 *
 * With `entityId` the route answers that ONE entity's batch instead: its facts,
 * its relations in either direction (deduped) and its own row when its
 * lifecycle status matches — complete and uncapped, newest first, exactly the
 * set `decide` promotes. WITHOUT it the behaviour is unchanged: the global
 * inbox, capped at `MemoryProposalService.INBOX_LIMIT`.
 */
export async function listMemoryProposals(
  status: MemoryFactStatus = 'PENDING',
  entityId?: string,
): Promise<MemoryProposalView[]> {
  const { data } = await client.get<MemoryProposalView[]>('/api/v1/memory/proposals', {
    // An absent entity id must not become an empty param: the global inbox
    // request stays exactly what it was.
    params: entityId === undefined ? { status } : { status, entityId },
  });
  return data;
}

/** `POST /proposals/{id}/decide`. */
export async function decideMemoryProposal(
  id: string,
  decision: MemoryProposalDecision,
): Promise<MemoryProposalView> {
  applyOperatorHeaders();
  const { data } = await client.post<MemoryProposalView>(
    `/api/v1/memory/proposals/${id}/decide`,
    { decision },
  );
  return data;
}

/** `GET /recall?q=` — the same ranked slice a prompt receives. */
export async function recallMemory(
  q: string,
  limit?: number,
): Promise<MemoryRecallPreview> {
  const { data } = await client.get<MemoryRecallPreview>('/api/v1/memory/recall', {
    params: { q, ...(limit !== undefined ? { limit } : {}) },
  });
  return data;
}

/** `GET /runs/{id}/context-manifest` — per-turn assembly metadata, never the prompt. */
export async function runContextManifest(runId: string): Promise<MemoryManifestView[]> {
  const { data } = await client.get<MemoryManifestView[]>(
    `/api/v1/memory/runs/${runId}/context-manifest`,
  );
  return data;
}

/** Drops absent optional params so the query string matches what the server accepts. */
function buildParams(params: ListMemoryEntitiesParams): Record<string, string | number> {
  const out: Record<string, string | number> = {};
  if (params.kind) out.kind = params.kind;
  if (params.q) out.q = params.q;
  if (params.status) out.status = params.status;
  if (params.limit !== undefined) out.limit = params.limit;
  return out;
}
