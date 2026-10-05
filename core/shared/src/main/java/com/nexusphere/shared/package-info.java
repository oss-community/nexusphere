/**
 * Cross-module primitives shared by every Nexusphere core module.
 *
 * <p>Only truly cross-module abstractions belong here: identifiers, the execution context,
 * the domain event envelope, the time abstraction and the error model. Business concepts
 * stay in the module that owns them. This module has no runtime framework dependencies; the
 * only annotation below tells Spring Modulith that every package here is public to all modules.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.nexusphere.shared;

import org.springframework.modulith.ApplicationModule;
