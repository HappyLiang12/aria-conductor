import client from './client';

export interface Skill {
  id: string;
  name: string;
  category?: string;
  description?: string;
  enabled?: boolean;
  stage?: string;
  tier?: string;
}

/** Fetch the real skill registry (backs the Configure → Skills tab and `/` slash menu). */
export async function listSkills(opts?: { enabled?: boolean; stage?: string }): Promise<Skill[]> {
  const { data } = await client.get<Skill[]>('/api/v1/skills', { params: opts });
  return data;
}
