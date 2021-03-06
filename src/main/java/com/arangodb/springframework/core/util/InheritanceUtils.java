/**
 * 
 */
package com.arangodb.springframework.core.util;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.data.mapping.PersistentEntity;
import org.springframework.data.mapping.context.MappingContext;

import com.arangodb.springframework.annotation.Document;
import com.arangodb.springframework.config.ArangoEntityClassScanner;
import com.arangodb.springframework.core.mapping.ArangoPersistentEntity;
import com.arangodb.springframework.core.mapping.ArangoPersistentProperty;

/**
 * Utilities to facilitate support for inheritance in persisted entities (following DRY principles, & other best practices). At present it is used for optimal 
 * support of inheritance in associations involving classes that have a declared @Document annotation that are persisted in a dedicated collection 
 * (this is similar to TABLE_PER_CLASS type of inheritance in JPA) (including support for associations of a {@link Collection} type).
 * E.g., this helps having clean records of entities (with inheritance): <br/>
 *  {"mainSkill":"Java","name":"Reşat"} <br/>
 * as opposed to <br/>
 *  {"_class":"com.arangodb.springframework.core.convert.InheritanceSupportTest$DeveloperSubclass","mainSkill":"Perl","name":"Kevin"}
 * 
 * @author Reşat SABIQ
 */
// This approach is superior to was merged after this push request (18) was submitted as part of pull request 33, because that pull request stores fully-qualified 
// class name for each record (with or without inheritance) which is completely unnecessary for classes that have a declared @Document annotation that are 
// persisted in a dedicated collection, because there is already an entire COLLECTION/TABLE dedicated to the class involved. 
// Thus, by comparison, this approach optimizes:
// 1. disk space, 
// 2. memory, 
// 3. bandwidth & 
// 4. CPU usage, 
// 5. avoids additional operating expenses (due to the above), 
// 6. avoids extreme visual clutter when looking at the data, 
// 7. & its likely negative effects on productivity, 
// 8. doesn't entail unnecessary tight-coupling of DB records to Java classes, and 
// 9. avoids any potential negative impact on latency
// by not having to deal with unnecessary overhead entailed by processing, storage, & retrieval of a lot of unnecessary data.
// Down the road, this approach could also facilitate optimal implementations for other main-stream inheritance types in associations.
public class InheritanceUtils {
	// (Something like) this could even be made configurable (via arangodb.properties):
	private static final boolean QUASI_BRUTE_FORCE_SCANNING_INSTEAD_OF_EXCEPTION_4_INHERITANCE_SUPPORT = true;
	private static final PackageHelper packageHelper = PackageHelper.getInstance();
	
	private InheritanceUtils() {}
	
	/**
	 * Finds the (sub)type best matching the reference Id containing the actual type name. 
	 * The (sub)type found has the same class name as that contained in {@code source},
	 * & is of the same type as or extends the type of {@code property}.
	 * 
	 * @param source		Type-containing reference id.
	 * @param propertyType	Reference property type.
	 * @param context		Mapping context to check while matching.
	 * 
	 * @return	The (sub)type best matching {@code source}. In cases with single-collection for multiple classes, this is not guaranteed to be the exact match 
	 * 			(which needs to be determined based on persisted type info).
	 */
	public static Class<?> determineInheritanceAwareReferenceType(
		final Object source,
		final Class<?> propertyType,
		final MappingContext<? extends ArangoPersistentEntity<?>, ArangoPersistentProperty> context) {
		Class<?> type = null;
		String src = source.toString();
		String entityName = MetadataUtils.determineCollectionFromId(src);
		// At present, a subclass would quite likely have a simple class name reflected in Id that is different from the property's (compile-time) type name (ignoring case):
		handleInheritance:
		if (!verifyMatch(propertyType, entityName)) {
			// Find the matching entity in the context:
			for (PersistentEntity<?,?> entityNode : context.getPersistentEntities()) {
				if (verifyMatch(entityNode.getType(), entityName)) {
					// This is the type of the subclass that's needed for inheritance support:
					type = entityNode.getType();
					break handleInheritance;
				}
			}
			
			/* It's not in context: search step-by-step so that we stop sooner rather than later: */
			// 1. Check same & child packages (sub-classes would most likely be found this way)
			Package samePackage = propertyType.getPackage();
			type = findAssignablesReflectively(samePackage.getName(), entityName, propertyType);
			if (type == null) {
				String parentPackage = samePackage.getName().substring(0, samePackage.getName().lastIndexOf('.'));
				// 2. Check parent & sibling packages (& sub-packages)
				type = findAssignablesReflectively(parentPackage, entityName, propertyType);
				if (type == null) {
					// 3. Quasi-brute-force:
					if (QUASI_BRUTE_FORCE_SCANNING_INSTEAD_OF_EXCEPTION_4_INHERITANCE_SUPPORT) {
						List<String> packagesWorthScanning = packageHelper.getAllPackagesWorthScanning();
						for (String tld : packagesWorthScanning) {
							type = findAssignablesReflectively(tld, entityName, propertyType);
							if (type != null)
								break;
						}
					} else // CHECKME: Consider whether to throw an exception like the one below instead of quasi-brute-force scanning (which could take .3 seconds or so)
						throw new IllegalStateException("Please add the package for \"" +entityName+ "\" to the list of base packages to be scanned when configuring ArangoDB spring-data");

				}
			}
		}
		if (type == null) // no subclass involved
			type = propertyType;
		return type; // This would be a bit lighter on CPU, but would involve a bit more GC: Pair.of(type, inContext ? Boolean.TRUE : Boolean.FALSE);
	}

	/**
	 * Reflectively finds match assignable to {@code property}'s type.
	 * 
	 * @param basePackage	package to start searching under.
	 * @param entityName	entity name to search for.
	 * @param property		property to whose type the matches must be assignable.
	 * 
	 * @return	valid match or null.
	 */
	private static Class<?> findAssignablesReflectively(
			final String basePackage, 
			final String entityName,
			final Class<?> propertyType) {
		try {
			Set<Class<?>> subTypes = ArangoEntityClassScanner.scanForEntities(propertyType, basePackage);
//			if (subTypes != null) // doesn't return null
				for (Class<?> klass : subTypes)
					if (verifyMatch(klass, entityName))
						return klass;
		} catch (ClassNotFoundException e) { // shouldn't happen in real-world
			e.printStackTrace();
		}
		return null;
	}
	
	/**
	 * Verifies that {@code candidateType} is a {@link Document}, whose explicit or implicit document/entity name matches {@code entityName}.
	 * 
	 * @param candidateType	candidate type to verify.
	 * @param entityName	entity name to match.
	 * 
	 * @return	true if a valid {@link Document} match, false otherwise.
	 */
	private static boolean verifyMatch(final Class<?> candidateType, final String entityName) {
		Document doc = candidateType.getAnnotation(Document.class);
		if (doc != null) {
			// First compare with explicit (annotation) name, then with implicit (class) name:
			if (entityName.equals(doc.value()))
				return true;
			String name = candidateType.getSimpleName();
			name = Character.toLowerCase(name.charAt(1)) + name.substring(1);
			return entityName.equals(name);
		}
		return false;
	}
}
