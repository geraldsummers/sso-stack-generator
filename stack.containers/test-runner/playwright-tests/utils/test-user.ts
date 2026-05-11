export type TestUser = {
  username: string;
  password: string;
  email: string;
  groups: string[];
  givenName?: string;
  familyName?: string;
  commonName?: string;
  displayName?: string;
  fullName?: string;
  totpSecret?: string;
  managed?: boolean;
};

export type UserProfile = {
  username: string;
  email: string;
  givenName?: string;
  familyName?: string;
  commonName?: string;
  displayName?: string;
  fullName?: string;
};
