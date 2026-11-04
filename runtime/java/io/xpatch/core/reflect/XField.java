package io.xpatch.core.reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Objects;

public class XField {

    public interface XFieldFilter<TYPE> {
        boolean onField(Field field, TYPE type);
    }

    public static void getStaticFields(Class<?> cls, XFieldFilter xFieldFilter) {
        for (Field field : cls.getDeclaredFields()) {
            field.setAccessible(true);
            if (Modifier.isStatic(field.getModifiers())) {
                try {
                    xFieldFilter.onField(field, field.get(null));
                } catch (Exception unused) {
                }
            }
        }
    }

    public static void getObjFields(Object obj, XFieldFilter xFieldFilter) {
        for (Field field : obj.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            try {
                xFieldFilter.onField(field, field.get(obj));
            } catch (Exception unused) {
            }
        }
    }

    public static void getObjFields2(Object obj, Class<?> cls, XFieldFilter xFieldFilter) {
        for (Field field : cls.getDeclaredFields()) {
            field.setAccessible(true);
            try {
                xFieldFilter.onField(field, field.get(obj));
            } catch (Exception unused) {
            }
        }
    }

    public static XFieldBuilder clz(String str) {
        return new XFieldBuilder().clazz(XClass.loadEx(str));
    }

    public static XFieldBuilder clz(Class<?> cls) {
        return new XFieldBuilder().clazz(cls);
    }

    public static XFieldBuilder obj(Object obj) {
        return new XFieldBuilder().obj(obj);
    }

    public static class XFieldBuilder {
        private XFieldFilter filter;
        private boolean isStrictMode;
        private String name;
        private Object obj;
        private Class<?> objClass;
        private boolean onlyStatic;
        private Class<?> type;

        private XFieldBuilder() {
        }

        public XFieldBuilder name(String str) {
            this.name = str;
            return this;
        }

        public XFieldBuilder type(Class<?> cls) {
            this.type = cls;
            return this;
        }

        public XFieldBuilder clazz(Class<?> cls) {
            this.objClass = cls;
            return this;
        }

        public <FilterTYPE> XFieldBuilder filter(XFieldFilter<FilterTYPE> xFieldFilter) {
            this.filter = xFieldFilter;
            return this;
        }

        public XFieldBuilder obj(Object obj) {
            this.obj = obj;
            return this;
        }

        public XFieldBuilder onlyStatic(boolean z) {
            this.onlyStatic = z;
            return this;
        }

        public XFieldBuilder strict(boolean z) {
            this.isStrictMode = z;
            return this;
        }

        public <T> T get() {
            return (T) doFind();
        }

        public <T> void set(T t) throws IllegalAccessException {
            String str;
            Object obj = null;
            XFieldFilter xFieldFilter;
            Class<?> cls = this.objClass;
            if (cls == null) {
                Object obj2 = this.obj;
                cls = obj2 == null ? null : obj2.getClass();
            }
            if (this.obj == null) {
                this.onlyStatic = true;
            }
            Objects.requireNonNull(cls, "find clz can't be null.(name = " + this.name + ", type = " + this.type + ", strict = " + this.isStrictMode + ")");
            for (Class<?> superclass = cls; superclass != null; superclass = superclass.getSuperclass()) {
                for (Field field : superclass.getDeclaredFields()) {
                    Class<?> type = field.getType();
                    String name = field.getName();
                    if ((!this.onlyStatic || Modifier.isStatic(field.getModifiers())) && ((str = this.name) == null || name.equals(str))) {
                        Class<?> cls2 = this.type;
                        if (cls2 != null && !(this.isStrictMode ? type.equals(cls2) : (cls2.isAssignableFrom(type) || type.equals(this.type)))) {
                            continue;
                        }
                        field.setAccessible(true);
                        try {
                            obj = Modifier.isStatic(field.getModifiers()) ? field.get(null) : field.get(this.obj);
                        } catch (Exception unused) {
                            obj = null;
                        }
                        xFieldFilter = this.filter;
                        if (xFieldFilter == null || xFieldFilter.onField(field, obj)) {
                            if (Modifier.isStatic(field.getModifiers())) {
                                field.set(null, t);
                            } else {
                                field.set(this.obj, t);
                            }
                            return;
                        }
                    }
                }
            }
            throw new RuntimeException("No such field(name=" + this.name + ",type=" + this.type + ",class=" + cls + ",strict=" + this.isStrictMode + ") found.");
        }

        public <T> T doFind() {
            String str;
            T t = null;
            XFieldFilter xFieldFilter;
            Class<?> cls = this.objClass;
            if (cls == null) {
                Object obj2 = this.obj;
                cls = obj2 == null ? null : obj2.getClass();
            }
            if (this.obj == null) {
                this.onlyStatic = true;
            }
            Objects.requireNonNull(cls, "find clz can't be null.(name = " + this.name + ",type = " + this.type + ",strict = " + this.isStrictMode + ")");
            for (Class<?> superclass = cls; superclass != null; superclass = superclass.getSuperclass()) {
                for (Field field : superclass.getDeclaredFields()) {
                    Class<?> type = field.getType();
                    String name = field.getName();
                    if ((!this.onlyStatic || Modifier.isStatic(field.getModifiers())) && ((str = this.name) == null || name.equals(str))) {
                        Class<?> cls2 = this.type;
                        if (cls2 != null && !(this.isStrictMode ? type.equals(cls2) : (cls2.isAssignableFrom(type) || type.equals(this.type)))) {
                            continue;
                        }
                        field.setAccessible(true);
                        try {
                            t = (T) (Modifier.isStatic(field.getModifiers()) ? field.get(null) : field.get(this.obj));
                        } catch (Exception unused) {
                            t = null;
                        }
                        xFieldFilter = this.filter;
                        if (xFieldFilter == null || xFieldFilter.onField(field, t)) {
                            return t;
                        }
                    }
                }
            }
            throw new RuntimeException("No such field(name=" + this.name + ",type=" + this.type + ",class=" + cls + ",strict=" + this.isStrictMode + ") found.");
        }
    }
}
