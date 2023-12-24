# SmatterDI

A small dependency injection framework made for the smatterbuild build system

## Usage

This dependency framework is rather different to the more commontype injection frameworks
in that it is my spin of how DI works.It is heavily influenced by gradle.
Due to that it is not compatible with the standard javax.inject annotations and instead defines
it's own annotations.

The first step of using this framework is to define an InjectionContext.
The built-in `SimpleInjectionContextImpl` is usually all that is needed, but it is possible to
implement the interface on your own.

Then one needs to define a `ObjectAllocator`, this can best be done by subclassing `CDObjectAllocator`,
which already implements most of the framework's inner logic. However, in order to use the framework
the CDObjectAllocator's `defineClass` method needs to be implemented. This method is invoked
to load required subclasses in an arbitrary fashion. How exactly it is implemented is up to the
caller: Smatterbuild for example uses starloader-launcher's `TransformableClassloader#transformAndDefineClass`.

The penultimate step is to register your objects to the injection context as needed.
This can be done in three ways: `setImplementation(Class<T>, T)`, `setProvider(Class<T>, Supplier<T>)`
or via `@Autowire`. When using `@Autowire`, no further setup is needed.
Please be aware that at the moment objects are basically singletons , meaing that for every
injection context there can only be one object for any type. Attempts to have multiple ones
will overwrite the previous value, meaning that you will find rather odd results.

Lastly you want to actually create your objects. This is also the part where SmatterDI is
the most different to most frameworks, as you can pass arbitrary arguments to the constructor
of your classes. In order to allocate a value, simply call `ObjectAllocator.allocate(Class<T>, InjectionContext, Object...)`,
where as `Class<T>` is the type of the object you want to allocate. Please note that this
class may not be final as the framework depends on subclassing. It can be public or package-private,
however.

## Inject

At the heart of the dependency injection framework is the `@Inject` annotation.
When applied on a method, the method will be overriden (via subclassing) and return
the instance belonging to the return type of the method. This instance is obtained
eagerly via the injection context. It may be useful to cache the return value in
a field via  

Unlike most other dependency frameworks, this annotation cannot be applied on fields.
It cannot be applied on private or final methods, but may be applied on package-protected,
protected and public methods - abstract or not.
These are constraints imposed by SmatterDI using subclassing to implement it's features
and it is unlikely that this will change.

## Autowire

There are cases where Java imposes strict limit on what you can do with your instances,
especially inside constructors. This is the most noticable in cases where circular dependencies
exists. In order to break up these circular dependencies, a small trick is employed when using `@Autowire`.

To explain the issue and the solution at hand we will use an example from smatterbuild, which has
(in a condensed form) the following classes:

```java
abstract class Project {
	@Inject
	protected abstract TaskContainer getTasks(); 
}

abstract class TaskContainer {
	@Inject
	protected abstract Project getProject();
}
```

The above setup works well enough, but what happens if both assign the values of the methods to fields
or otherwise use them in their constructor?

```java
abstract class Project {
	private final TaskContainer tasks = this.getTasks();
	@Inject
	protected abstract TaskContainer getTasks(); 
}

abstract class TaskContainer {
	private final Project project = this.getProject();
	@Inject
	protected abstract Project getProject();
}
```

The above will not work though, as to instantiate Project you'd need TaskContainer and vice-versa.
But by applying `@Autowire` to both types one can avoid this problem. Technically speaking it isn't
needed to apply it on both types (just on the type that is first initialized), and as such the
following snippet also works:

```java
@Autowire
abstract class Project {
	private final TaskContainer tasks = getTasks();
	@Inject
	protected abstract TaskContainer getTasks(); 
}

abstract class TaskContainer {
	private final Project project = this.getProject();
	@Inject
	protected abstract Project getProject();
}

// And also:
injectCtx.setProvider(TaskContainer.class, () -> {
	return allocator.allocate(TaskContainer.class, injectCtx);
});
```

To figure out best why and how this works, one should look into the bytecode that
is generated by CDObjectAllocator, which looks a bit like follows (some liberties were taken though,
for brevity):


```java
final class generated_Project extends Project {
	private final InjectionContext context;
	private boolean autowired = false;

	public generated_Project(InjectionContext context) {
		this.context = context;
		super();
		this.autowire();
	}

	public final TaskContainer getTasks() {
		this.autowire();
		return this.context.getInstance(TaskContainer.class);
	}

	private final void autowire() {
		if (this.autowired) {
			return;
		}
		this.autowired = true;
		this.context.autowire(Project.class, this);
	}
}

final class generated_TaskContainer extends TaskContainer {
	private final InjectionContext context;

	public generated_TaskContainer(InjectionContext context) {
		this.context = context;
		super();
	}

	public final Project getProject() {
		return this.context.getInstance(Project.class);
	}
}
```