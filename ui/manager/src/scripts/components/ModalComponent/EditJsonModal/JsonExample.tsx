import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { getPostExample } from '../../utils/EddiConfigExampleData';
import Parser from '../../utils/Parser';
import styles from '../ModalComponent.styles';
import '../ModalComponent.styles.scss';

interface IState {
  showExample: boolean;
}

interface IPublicProps {
  type: string;
}

interface IPrivateProps extends IPublicProps {}

class JsonExample extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      showExample: false,
    };
  }

  onButtonClick = () => {
    this.setState({
      showExample: !this.state.showExample,
    });
  };

  render() {
    const typeName = Parser.getPluginName(this.props.type, false);
    return (
      <div>
        <button onClick={this.onButtonClick} style={styles.collapsibleButton}>
          <div>{`${
            this.state.showExample ? 'Hide' : 'Show'
          } ${typeName.toLowerCase()} example data`}</div>
          <div style={styles.collapsibleRightSign}>
            {this.state.showExample ? '-' : '+'}
          </div>
        </button>
        {this.state.showExample && (
          <div style={styles.exampleData}>
            {getPostExample(this.props.type)}
          </div>
        )}
      </div>
    );
  }
}

const ComposedJsonExample: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  setDisplayName('JsonExample'),
)(JsonExample);

export default ComposedJsonExample;
