/*
 * Copyright (C) 2015 see CREDITS.txt
 * All rights reserved.
 */

package fi.uef.envi.emrooz.api.time;

import org.openrdf.model.URI;

import fi.uef.envi.emrooz.api.AbstractEntity;
import fi.uef.envi.emrooz.api.TemporalEntityVisitor;

/**
 * <p>
 * Title: TemporalEntity
 * </p>
 * <p>
 * Description:
 * </p>
 * <p>
 * Project: Emrooz
 * </p>
 * <p>
 * Copyright: Copyright (C) 2015
 * </p>
 * 
 * @author Markus Stocker
 */

public abstract class TemporalEntity extends AbstractEntity {

	public TemporalEntity(URI id, URI type) {
		super(id, type);
	}

	public abstract void accept(TemporalEntityVisitor visitor);

}
