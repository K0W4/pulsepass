import Keycloak from 'keycloak-js';
import { papeis } from './util';

declare global {
  interface Window {
    __CONFIG__?: { keycloakUrl: string; realm: string; clientId: string };
  }
}

const cfg = window.__CONFIG__ ?? { keycloakUrl: 'http://localhost:8081', realm: 'pulsepass', clientId: 'pulsepass-api' };
const kc = new Keycloak({ url: cfg.keycloakUrl, realm: cfg.realm, clientId: cfg.clientId });

export async function iniciar(): Promise<void> {
  await kc
    .init({
      onLoad: 'check-sso',
      pkceMethod: 'S256',
      responseMode: 'query',
      checkLoginIframe: false,
      silentCheckSsoRedirectUri: `${location.origin}/silent-check-sso.html`,
    })
    .catch(() => false);
}

export function logado(): boolean {
  return Boolean(kc.authenticated);
}

export function usuario(): string {
  return String(kc.tokenParsed?.['email'] ?? kc.tokenParsed?.['preferred_username'] ?? '');
}

export function temPapel(papel: string): boolean {
  return papeis(kc.tokenParsed as { realm_access?: { roles?: string[] } }).includes(papel);
}

export function entrar(): void {
  sessionStorage.setItem('rota-apos-login', location.hash || '#/');
  void kc.login({ redirectUri: `${location.origin}/` });
}

export function cadastrar(): void {
  sessionStorage.setItem('rota-apos-login', location.hash || '#/');
  void kc.register({ redirectUri: `${location.origin}/` });
}

export function sair(): void {
  void kc.logout({ redirectUri: `${location.origin}/` });
}

export async function token(): Promise<string | undefined> {
  if (!kc.authenticated) return undefined;
  await kc.updateToken(30).catch(() => undefined);
  return kc.token;
}
