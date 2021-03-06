package us.ceka.dao;


public interface AbstractDao <PK, T> { 
	public T getByKey(PK key);
	
    public void persist(T entity);
    public void update(T entity); 
    public void merge(T entity);
    public void delete(T entity);

}
