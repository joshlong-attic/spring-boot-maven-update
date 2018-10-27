package com.example.updater;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.util.FileCopyUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Log4j2
@SpringBootApplication
public class UpdaterApplication {

	private final String SNAPSHOTS = "spring-snapshots";
	private final String MILESTONES = "spring-milestones";
	private final String BOOT_VERSION = "2.0.6.RELEASE";
	private final String CLOUD_VERSION = "Finchley.SR2";

	private final File rootDir;

	UpdaterApplication(@Value("file://${user.home}/code/spring-tips") File rootDir) {
		this.rootDir = rootDir;
	}

	private void processFile(Path path, BasicFileAttributes attrs) throws Exception {
		File file = path.toFile();
		if (!file.getName().equalsIgnoreCase("pom.xml")) {
			return;
		}
		DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
		Document doc = dBuilder.parse(file);
		doc.getDocumentElement().normalize();

		// make sure we have the correct Maven repositories
		Stream<Node> springRepositories = this.nodeStream(doc.getElementsByTagName("repository"))
			.filter(node ->
				this.nodeStream(node.getChildNodes())
					.filter(childNode -> childNode.getNodeName().equalsIgnoreCase("id"))
					.map(Node::getTextContent)
					.anyMatch(id -> id.equalsIgnoreCase(MILESTONES) || id.equalsIgnoreCase(SNAPSHOTS))
			);
		long countOfSpringRepositories = springRepositories.count();
		if (countOfSpringRepositories != 2) {
			this.addRepositoriesTo(file, doc);
		}

		// make sure we have the correct Spring Boot version
		this.nodeStream(doc.getElementsByTagName("parent"))
			.findFirst()
			.ifPresent(parentNode -> {
					this.nodeStream(parentNode.getChildNodes())
						.filter(n -> n.getNodeName().equalsIgnoreCase("version"))
						.forEach(n -> n.setTextContent(BOOT_VERSION));
				}
			);

		// make sure we have the correct Spring Cloud version
		this.nodeStream(doc.getElementsByTagName("dependencyManagement"))
			.flatMap(x -> this.nodeStream(x.getChildNodes()))
			.flatMap(x -> this.nodeStream(x.getChildNodes()))
			.forEach(this::springCloudDependency);

		String resultingXml = this.toXml(doc);
		log.info(resultingXml);

		try (FileWriter fw = new FileWriter(file)) {
			FileCopyUtils.copy(resultingXml, fw);
		}
	}

	private Optional<Node> artifactLeafNodeByTagName(Node root, String tagName) {
		return this.nodeStream(root.getChildNodes())
			.filter(x -> x.getNodeName().equalsIgnoreCase(tagName))
			.findAny();
	}

	private void springCloudDependency(Node node) {
		this.nodeStream(node.getChildNodes())
			.filter(n -> n.getNodeName().equalsIgnoreCase("artifactId"))
			.filter(n -> n.getTextContent().equalsIgnoreCase("spring-cloud-dependencies"))
			.findFirst()
			.ifPresent(artifactId -> this.artifactLeafNodeByTagName(node, "version")
				.ifPresent(v -> v.setTextContent(CLOUD_VERSION))
			);
	}

	private String toXml(Document doc) throws TransformerException, IOException {
		DOMSource domSource = new DOMSource(doc);
		try (StringWriter writer = new StringWriter()) {
			StreamResult result = new StreamResult(writer);
			TransformerFactory tf = TransformerFactory.newInstance();
			Transformer transformer = tf.newTransformer();
			transformer.transform(domSource, result);
			return result.getWriter().toString();
		}
	}

	private void addRepositoriesTo(File pom, Document doc) {
		log.info(pom.getAbsolutePath() + " needs the required pom.xml files");
		Stream<Node> repositories = this.nodeStream(doc.getElementsByTagName("repositories"));
		Node node = repositories
			.findFirst()
			.orElseGet(() -> {
				Element element = doc.createElement("repositories");
				return doc.getElementsByTagName("project")
					.item(0)
					.appendChild(element);
			});

		this.addRepository(doc, node, SNAPSHOTS, "https://repo.spring.io/snapshot", true);
		this.addRepository(doc, node, MILESTONES, "https://repo.spring.io/milestone", false);
	}

	private void addRepository(Document doc, Node repositories, String id, String url, boolean snapshotsEnabled) {

		Element repositoryElement = doc.createElement("repository");

		Element idElement = doc.createElement("id");
		idElement.setTextContent(id);

		Element nameElement = doc.createElement("name");
		nameElement.setTextContent(id);

		Element urlElement = doc.createElement("url");
		urlElement.setTextContent(url);

		Element snapshotsEnabledElement = doc.createElement("enabled");
		snapshotsEnabledElement.setTextContent(Boolean.toString(snapshotsEnabled));

		Element snapshotsElement = doc.createElement("snapshots");
		snapshotsElement.appendChild(snapshotsEnabledElement);

		repositoryElement.appendChild(idElement);
		repositoryElement.appendChild(nameElement);
		repositoryElement.appendChild(urlElement);
		repositoryElement.appendChild(snapshotsElement);

		repositories.appendChild(repositoryElement);
	}

	private Stream<Node> nodeStream(NodeList nodeList) {
		return IntStream.range(0, nodeList.getLength()).mapToObj(nodeList::item);
	}

	@EventListener(ApplicationReadyEvent.class)
	public void walk() throws Exception {

		Files.walkFileTree(this.rootDir.toPath(), new FileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				try {
					processFile(file, attrs);
				}
				catch (Exception e) {
					throw new RuntimeException(e);
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				return FileVisitResult.CONTINUE;
			}
		});
	}

	public static void main(String[] args) {
		SpringApplication.run(UpdaterApplication.class, args);
	}
}
