package com.webank.cmdb.support.cache;

import com.google.common.collect.ImmutableMap;
import com.webank.cmdb.domain.AdmCiTypeAttr;
import com.webank.cmdb.support.exception.CmdbException;
import org.apache.commons.beanutils.BeanMap;
import org.h2.engine.Domain;
import org.hibernate.proxy.HibernateProxy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;
import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

//@Component("staticDomainCacheManager")
public class StaticDomainCacheManager<D,R extends JpaRepository<D,?>> extends RequestScopedCacheManager {
    private Map<String,Class> cacheDomains = ImmutableMap.of("AdmCiTypeAttr", AdmCiTypeAttr.class);

    @Autowired
    private EntityManager entityManager;

    @Override
    protected Cache createCache(String domainName){
        Class domainClazz = cacheDomains.get(domainName);
        Cache cache = new DomainCache(domainName,domainClazz,entityManager);
        return cache;
    }

    static public class DomainCache extends ConcurrentMapCache{
        private  volatile  boolean loaded = false;
        private Class domainClazz = null;
        private EntityManager entityManager;
        private List<Object> domainObjs = new ArrayList<>();
        private Map<String,Map<Object,List<Object>>> indexByProperty = new HashMap<>();

        public DomainCache(String name,Class domainClazz,EntityManager entityManager) {
            super(name);
            this.domainClazz = domainClazz;
            this.entityManager = entityManager;
        }

        public void init(){
            if(loaded == false){
                loadDomainData();
            }
        }

        private void generateIndex(){
            indexByProperty.clear();
            Field[] fields = domainClazz.getDeclaredFields();
            List<String> properties = Arrays.stream(fields).map(Field::getName).collect(Collectors.toList());
            domainObjs.forEach(domain -> {
                BeanMap domainMap = new BeanMap(domain);
                properties.forEach(property -> {
                    Object value = domainMap.get(property);
                    if(value == null || value instanceof HibernateProxy)
                        return;
                    Map<Object,List<Object>> valueMap = indexByProperty.get(property);
                    if(valueMap == null){
                        valueMap = new HashMap<>();
                        indexByProperty.put(property,valueMap);
                    }
                    List domainObjs = valueMap.get(value);
                    if(domainObjs == null){
                        domainObjs = new LinkedList();
                        valueMap.put(value,domainObjs);
                    }
                    domainObjs.add(domain);
                });
            });
        }

        @Override
        @Nullable
        protected Object lookup(Object key) {
            if(! (key instanceof DomainCacheKey)){
                return null;
            }

            if(loaded == false){
                loadDomainData();
            }

            DomainCacheKey cacheKey = (DomainCacheKey)key;
            //List foundList= domainObjs.stream().filter(domain -> {return match(cacheKey,domain);}).collect(Collectors.toList());
            List foundList = fetchFromIndex(cacheKey);
            return foundList;
        }

        private List fetchFromIndex(DomainCacheKey key){
            List resultDomainObjs = new LinkedList();
            List<String> properties = key.getProperties();
            for(int i=0;i< properties.size();i++){
                String property = properties.get(i);
                Map<Object,List<Object>> valueMap = indexByProperty.get(property);
                if(valueMap != null){
                    Object keyValue = key.getValues().get(i);
                    if(keyValue == null)
                        continue;
                    List domainObjs = valueMap.get(keyValue);
                    if(domainObjs == null){
                        resultDomainObjs.clear();
                    }else {
                        if (resultDomainObjs.size() == 0) {
                            resultDomainObjs.addAll(domainObjs);
                        } else {
                            resultDomainObjs.retainAll(domainObjs);
                        }
                    }
                }
            }
            return  resultDomainObjs;
        }

        private boolean match(DomainCacheKey key,Object domainObj){
            BeanMap domainMap = new BeanMap(domainObj);
            List<String> properties = key.getProperties();
            List<Object> values = key.getValues();
            if(properties.size() != values.size()){
                throw new CmdbException("DomainCacheKey's properties and values don't have same size.");
            }

            for(int i=0;i<properties.size();i++){
                String property = properties.get(i);
                Object domainValue = domainMap.get(property);
                Object curVal = values.get(i);
                if(domainValue==null){
                    if(curVal!=null){
                        return false;
                    }
                }else {
                    if (!domainValue.equals(curVal)) {
                        return false;
                    }
                }
            }
            return true;
        }

        @Override
        public void put(Object key, @Nullable Object value) {
            return;
        }

        private synchronized void loadDomainData(){
            String loadAllSql = domainClazz.getSimpleName() + ".findAll";
            TypedQuery query = entityManager.createNamedQuery(loadAllSql,domainClazz);
            List result = query.getResultList();
            if(result != null && result.size()>0){
                domainObjs = result;
            }
            generateIndex();
            loaded = true;
        }
    }

    static public class DomainCacheKey{
        private List<String> properties = new ArrayList<>();
        private List<Object> values = new ArrayList<>();

        public DomainCacheKey(List<String> properties,List<Object> values){
            this.properties = properties;
            this.values = values;
        }

        public List<String> getProperties() {
            return properties;
        }

        public void setProperties(List<String> properties) {
            this.properties = properties;
        }

        public List<Object> getValues() {
            return values;
        }

        public void setValues(List<Object> values) {
            this.values = values;
        }
    }
}