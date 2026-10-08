import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts'
import { ApiError, LedgerApi } from '../api/client'

let manager: Promise<UserManager | null> | null = null

export function userManager(): Promise<UserManager | null> {
  if (!manager) {
    manager = new LedgerApi(null).oidc()
      .then((info) => new UserManager({
        authority: info.issuer,
        client_id: info.clientId,
        redirect_uri: `${window.location.origin}/callback`,
        post_logout_redirect_uri: window.location.origin,
        response_type: 'code',
        scope: 'openid profile email',
        userStore: new WebStorageStateStore({ store: window.sessionStorage }),
      }))
      .catch((error: unknown) => {
        if (error instanceof ApiError && error.status === 404) {
          return null
        }
        manager = null
        throw error
      })
  }
  return manager
}

export function bearerOf(user: User): string {
  const access = user.access_token
  return access && access.split('.').length === 3 ? access : user.id_token ?? access
}
