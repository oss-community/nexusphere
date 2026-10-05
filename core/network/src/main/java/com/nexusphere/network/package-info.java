/**
 * Network sovereignty boundary: lifecycle of independently governed networks.
 *
 * <p>Layout: {@code domain} (framework-free model), {@code application} (use cases and ports),
 * {@code api} (inbound adapters such as REST), {@code infrastructure} (persistence and outbound adapters).
 * Other modules may use only this module's {@code contract} package.
 */
package com.nexusphere.network;
