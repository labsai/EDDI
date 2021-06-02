import * as React from 'react';
import TopBarComponent from '../TopBar/TopBarComponent';
import styles from '../App.style';
import { compose, pure, setDisplayName } from 'recompose';
import Parser from '../utils/Parser';
import PluginList from '../Plugins/PluginList';
import {
  BEHAVIOR,
  GITCALLS,
  HTTPCALLS,
  OUTPUT,
  PROPERTYSETTER,
  REGULAR_DICTIONARY,
} from '../utils/EddiTypes';
import { pageEnum } from './pageEnum';

const eddiLogo = require('../../../public/images/eddi-logo.png');

interface IRouteProps {
  location: { search: string };
}

interface IState {
  filterText: string;
}

interface IProps extends IRouteProps {}

class ExtensionsPage extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      filterText: '',
    };
  }

  filter = (text: string) => {
    this.setState({ filterText: text });
  };

  getTypeFromQueryString() {
    const type = Parser.getQueryStrings(this.props.location.search).type;
    return type;
  }

  getEddiType(type: string) {
    switch (type) {
      case 'dictionary':
        return REGULAR_DICTIONARY;
      case 'behavior':
        return BEHAVIOR;
      case 'output':
        return OUTPUT;
      case 'httpCalls':
        return HTTPCALLS;
      case 'gitCalls':
        return GITCALLS;
      case 'property':
        return PROPERTYSETTER;
      default:
        return;
    }
  }

  render() {
    const type = this.getTypeFromQueryString();
    const eddiType = this.getEddiType(type);
    return (
      <div>
        <img src={eddiLogo} style={styles.eddiLogo} />
        <div>
          <TopBarComponent
            page={pageEnum[type]}
            filter={this.filter}
            type={eddiType}
          />
          <PluginList
            filterText={this.state.filterText}
            pluginType={eddiType}
          />
        </div>
      </div>
    );
  }
}

const ComposedExtensionsPage: React.ComponentClass<IProps>  = compose<IProps, IProps>(
  pure,
  setDisplayName('ExtensionsPage'),
)(ExtensionsPage);

export default ComposedExtensionsPage;
