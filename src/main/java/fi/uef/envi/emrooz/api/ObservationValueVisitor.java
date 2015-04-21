/*
 * Copyright (C) 2015 see CREDITS.txt
 * All rights reserved.
 */

package fi.uef.envi.emrooz.api;

import fi.uef.envi.emrooz.api.ssn.ObservationValueDouble;

/**
 * <p>
 * Title: ObservationValueVisitor
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

public interface ObservationValueVisitor {

	public void visit(ObservationValueDouble entity);
	
}
