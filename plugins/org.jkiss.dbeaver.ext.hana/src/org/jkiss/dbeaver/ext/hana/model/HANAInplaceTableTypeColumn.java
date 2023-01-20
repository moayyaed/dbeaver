/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2023 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ext.hana.model;

import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.impl.jdbc.struct.JDBCAttribute;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.struct.DBSObject;

public class HANAInplaceTableTypeColumn extends JDBCAttribute {

    DBSObject parent;

    HANAInplaceTableTypeColumn(DBSObject parent, String name, String typeName, int position, int length, int scale) {
        super(name, typeName, 0 /*valueType*/, position, length, scale, 0 /*precision*/, false /*required*/, false /*sequence*/);
        this.parent = parent;
    }

    @Override
    @Property(hidden = true)
    public boolean isRequired() { return false; }

    @Override
    @Property(hidden = true)
    public boolean isAutoGenerated() { return false; }
    
    @Override
    public String getDescription() { return null; }

    @Override
    public boolean isPersisted() { return true; }

    @Override
    public DBSObject getParentObject() { return parent; }

    @Override
    public DBPDataSource getDataSource() { return parent.getDataSource(); }
}
