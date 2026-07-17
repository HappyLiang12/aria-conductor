import client from './client';

export interface Skill {
  id?: string;
  name: string;
  category?: string;
  description?: string;
  enabled?: boolean;
}

/** Fetch the real skill registry (backs the Configure → Skills tab). */
export async function listSkills(): Promise<Skill[]> {
  const { data } = await client.get<Skill[]>('/api/v1/skills');
  return data;
}
