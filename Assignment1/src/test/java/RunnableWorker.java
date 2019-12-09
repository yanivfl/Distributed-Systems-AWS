import apps.MainWorkerClass;

public class RunnableWorker implements Runnable{
    @Override
    public void run() {
        String[] args = {"local"};
        try {
            MainWorkerClass.main(args);
        }catch(Exception e){
            System.out.println("Thread interrupted..."+e);
        }
    }
}
